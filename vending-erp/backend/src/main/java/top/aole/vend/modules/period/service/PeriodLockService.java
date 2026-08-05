package top.aole.vend.modules.period.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.period.domain.entity.PeriodLock;
import top.aole.vend.modules.period.mapper.PeriodLockMapper;
import top.aole.vend.modules.period.mapper.PeriodQueryMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 锁账服务(P0-2 锁账×补导)。
 *
 * 口径(§13.2-2,效力最高):
 * - 锁账线 = 未删锁记录最大 period,锁定该月**及之前所有月**;
 * - 锁定期之前单据禁改/禁红冲(抛异常);老板角色 + 强制备注可越权(角色占位,SSO 后接真角色);
 * - 锁账只管改单不管补导:锁后补导 → book_period=当前月,旧报表永不重算,"上期调整"行承接;
 * - 解锁限老板角色(占位)+强制备注,逻辑删最新锁记录,锁账线回落。
 */
@Service
@RequiredArgsConstructor
public class PeriodLockService {

    /** 老板角色占位值(SSO/user_role 接入前经 X-User-Role 头传入) */
    public static final String ROLE_BOSS = "老板";

    private static final Pattern PERIOD_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PeriodLockMapper lockMapper;
    private final PeriodQueryMapper queryMapper;
    private final OpLogService opLogService;

    // ============================== 查询 ==============================

    /** 当前锁账线(YYYY-MM,该月及之前全部锁定);无锁返回 null */
    public String lockLine() {
        return lockMapper.lockLine();
    }

    /** 某业务月是否已锁(≤锁账线) */
    public boolean isLocked(String period) {
        String line = lockLine();
        return line != null && period != null && period.compareTo(line) <= 0;
    }

    /** 锁账记录列表(新→旧) */
    public List<PeriodLock> listLocks() {
        return lockMapper.selectList(new LambdaQueryWrapper<PeriodLock>()
                .orderByDesc(PeriodLock::getPeriod));
    }

    // ============================== 锁 / 解锁 ==============================

    /** 锁账:锁定 period 及之前所有月份。只能锁已过去的月(当月要等出完报表,默认次月5日) */
    @Transactional(rollbackFor = Exception.class)
    public Long lock(String period, Long userId, String note) {
        assertPeriodFormat(period);
        String currentMonth = YearMonth.now().format(PERIOD);
        if (period.compareTo(currentMonth) >= 0) {
            throw new BizException(String.format(
                    "只能锁定已过去的月份:当前月 %s 尚未结束,不能锁 %s(默认每月5日出完上月报表后锁上月)",
                    currentMonth, period));
        }
        if (isLocked(period)) {
            throw new BizException(String.format("月份 %s 已在锁账线(%s)之内,无需重复锁定", period, lockLine()));
        }
        // 解锁过的同月旧记录(逻辑删)仍占 uk_lock_period 唯一键 → 复活旧行,不能 insert
        Long unlockedId = lockMapper.findUnlockedId(period);
        if (unlockedId != null) {
            lockMapper.relock(unlockedId, LocalDateTime.now(), userId, StrUtil.blankToDefault(note, null));
            opLogService.recordJson(userId, "锁账(重新锁定)", "period_lock", unlockedId,
                    null, "{\"period\":\"" + period + "\",\"note\":\"" + StrUtil.nullToEmpty(note) + "\"}");
            return unlockedId;
        }
        PeriodLock lock = new PeriodLock();
        lock.setPeriod(period);
        lock.setLockedAt(LocalDateTime.now());
        lock.setLockedBy(userId);
        lock.setLockNote(StrUtil.blankToDefault(note, null));
        lock.setCreateUser(userId);
        lockMapper.insert(lock);
        opLogService.recordJson(userId, "锁账", "period_lock", lock.getId(), null, JSONUtil.toJsonStr(lock));
        return lock.getId();
    }

    /**
     * 解锁:限老板角色(占位)+强制备注;只允许解当前锁账线那条(锁账线回落到上一条)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unlock(String period, Long userId, String note, String role) {
        assertPeriodFormat(period);
        if (!ROLE_BOSS.equals(role)) {
            throw new BizException("解锁限老板角色(当前角色:" + StrUtil.blankToDefault(role, "未知") + ")。"
                    + "角色占位实现,SSO/user_role 接入后按真实角色校验");
        }
        if (StrUtil.isBlank(note)) {
            throw new BizException("解锁必须填写备注(为什么要解锁、解锁后要改什么)");
        }
        String line = lockLine();
        if (line == null) {
            throw new BizException("当前没有任何锁账记录,无需解锁");
        }
        if (!line.equals(period)) {
            throw new BizException(String.format(
                    "只能解锁当前锁账线 %s(解锁后锁账线回落到上一条);要解更早月份请逐条回退", line));
        }
        PeriodLock lock = lockMapper.selectOne(new LambdaQueryWrapper<PeriodLock>()
                .eq(PeriodLock::getPeriod, period).last("LIMIT 1"));
        if (lock == null) {
            throw new BizException("锁账记录不存在:" + period);
        }
        String before = JSONUtil.toJsonStr(lock);
        lockMapper.deleteById(lock.getId()); // @TableLogic → 逻辑删,锁账线自动回落
        opLogService.recordJson(userId, "解锁(备注:" + note + ")", "period_lock", lock.getId(), before, null);
    }

    // ============================== 规则守卫(供 doc/红冲调用) ==============================

    /**
     * 锁账守卫:目标期间已锁 → 抛异常;老板角色 + 非空备注可越权(占位)。
     *
     * @param period       要动的单据入账/业务月
     * @param action       动作名(拼提示)
     * @param bossOverride 老板越权标记(占位,SSO 后按真实角色)
     * @param note         越权备注(越权时强制)
     */
    public void assertPeriodOperable(String period, String action, boolean bossOverride, String note) {
        if (!isLocked(period)) {
            return;
        }
        if (bossOverride) {
            if (StrUtil.isBlank(note)) {
                throw new BizException(String.format(
                        "%s 已锁账(锁账线 %s):老板越权必须填写备注", period, lockLine()));
            }
            return; // 越权放行,调用方负责把备注落到单据/op_log
        }
        throw new BizException(String.format(
                "%s 已锁账(锁账线 %s),禁止%s。旧报表永不重算;补录请入当月(上期调整行承接)," +
                        "或由老板角色带备注越权", period, lockLine(), action));
    }

    // ============================== 上期调整(报表取数口) ==============================

    /** 上期调整聚合:book_period=#{bookPeriod} 且 biz≠book 的销售/单据记录 */
    public Map<String, Object> priorAdjust(String bookPeriod) {
        assertPeriodFormat(bookPeriod);
        List<Map<String, Object>> saleLines = queryMapper.salePriorAdjust(bookPeriod);
        List<Map<String, Object>> docLines = queryMapper.docPriorAdjust(bookPeriod);
        BigDecimal saleAmount = saleLines.stream()
                .map(m -> new BigDecimal(String.valueOf(m.get("amount"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long saleRows = saleLines.stream()
                .mapToLong(m -> ((Number) m.get("rowCount")).longValue()).sum();
        Map<String, Object> result = new HashMap<>();
        result.put("bookPeriod", bookPeriod);
        result.put("saleLines", saleLines);   // 业务月+导入批次 → 行数/数量/实收金额
        result.put("saleRows", saleRows);
        result.put("saleAmount", saleAmount);
        result.put("docLines", docLines);     // 业务月+单据类型 → 单数/金额
        return result;
    }

    private void assertPeriodFormat(String period) {
        if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
            throw new BizException("月份格式必须为 YYYY-MM:" + period);
        }
        try {
            YearMonth.parse(period, PERIOD);
        } catch (Exception e) {
            throw new BizException("非法月份:" + period);
        }
    }
}
