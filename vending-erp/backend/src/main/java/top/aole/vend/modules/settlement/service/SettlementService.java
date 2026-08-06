package top.aole.vend.modules.settlement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.settlement.domain.entity.Settlement;
import top.aole.vend.modules.settlement.dto.SettlementDtos;
import top.aole.vend.modules.settlement.mapper.SettlementMapper;
import top.aole.vend.modules.settlement.mapper.SettlementQueryMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台结算双模式服务(M3-3,附录D 效力最高):
 *
 * PLATFORM(平台归集,§7.3 问3):
 *   待结算余额 = Σ(sale_record 正常−退款,settlement_id IS NULL;兑换/测试/线下不计);
 *   确认 = ①系统额快照(区间内待回填聚合) ②两差对账(漏单差/扣款差,超阈值±1元→差异挂起红灯)
 *          ③回填 sale_record.settlement_id(只碰 NULL 行,幂等)
 *          ④MoneyPostingEvent 两笔流水:货款结算 收(到账毛额=实际到账+手续费,NON_PL——收入已在
 *            销售报表按 sale_record 确认,虚账划转不再进利润表,防双记)+ 平台手续费 支(利润表
 *            "平台这个月吃掉了我多少钱"行)。净入账 = 实际到账,账户余额永远等于真钱。
 *
 * DIRECT(微信/支付宝直连):无待结算虚账;同一张表当"商户账单核对单"——确认只算
 *   系统销售额 vs 账单额 的漏单差,**不落收入流水、不回填**(钱已直连入账,真实到账靠
 *   M3-5 钱盘账户核对对总,避免双记;核对单只出差异报告)。
 *
 * UNSET:录入/确认一律拦(横幅指路设置中心);总览出"假设对比预览"(两模式各一版参考数,标非正式)。
 *
 * 防呆:mode_snap 录入时快照,确认时必须与当前模式一致(改模式后旧单只能作废重录);
 *       确认用条件更新抢占(待核对→核销中)防双击/并发双过账。
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    /** 两差红灯阈值(±元):超过即"差异挂起",resolve 补说明后收口 */
    public static final BigDecimal DIFF_THRESHOLD = new BigDecimal("1.00");

    public static final String REF_DOC_TYPE = "平台结算单";
    /** 凭证 refType(平台账单截图必传门禁) */
    public static final String ATTACH_REF_TYPE = "settlement";

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SettlementMapper settlementMapper;
    private final SettlementQueryMapper queryMapper;
    private final SettleModeService settleModeService;
    private final AccountMapper accountMapper;
    private final AttachmentService attachmentService;
    private final PeriodLockService periodLockService;
    private final OpLogService opLogService;
    private final ApplicationEventPublisher eventPublisher;

    // ============================== 总览(模式感知) ==============================

    public SettlementDtos.OverviewResp overview() {
        String mode = settleModeService.currentMode();
        SettlementDtos.OverviewResp resp = new SettlementDtos.OverviewResp();
        resp.setMode(mode);
        resp.setDiffThreshold(DIFF_THRESHOLD);
        if (SettleModeService.MODE_PLATFORM.equals(mode)) {
            resp.setPendingBalance(queryMapper.pendingBalance());
            resp.setPendingCount(queryMapper.pendingCount());
            resp.setPendingOldest(queryMapper.pendingOldest());
        } else if (SettleModeService.MODE_DIRECT.equals(mode)) {
            resp.setDirectNote("直连模式没有「平台待结算」虚账:销售发生即视为商户号入账。"
                    + "对账两条腿:①商户账单核对单(本页,只对差不动钱)②月度钱盘账户核对(M3-5)对真实到账。");
        } else {
            resp.setBanner(SettleModeService.UNSET_BANNER);
            BigDecimal pending = queryMapper.pendingBalance();
            SettlementDtos.HypothesisView pf = new SettlementDtos.HypothesisView();
            pf.setMode(SettleModeService.MODE_PLATFORM);
            pf.setTitle("假设=平台归集(PLATFORM)");
            pf.setExplain("这笔钱是「在途货款」:平台已代收、还没结给你,资产快照要加上它");
            pf.setAmount(pending);
            pf.setInformal(true);
            SettlementDtos.HypothesisView dr = new SettlementDtos.HypothesisView();
            dr.setMode(SettleModeService.MODE_DIRECT);
            dr.setTitle("假设=微信/支付宝直连(DIRECT)");
            dr.setExplain("同一笔销售额视为已直接进商户号:没有在途货款,只需按账单核对有没有漏单");
            dr.setAmount(pending);
            dr.setInformal(true);
            resp.getHypothesis().add(pf);
            resp.getHypothesis().add(dr);
        }
        return resp;
    }

    // ============================== 录入(模式感知校验) ==============================

    @Transactional(rollbackFor = Exception.class)
    public Long create(SettlementDtos.BillCreateReq req, Long userId, String operator) {
        String mode = settleModeService.currentMode();
        if (SettleModeService.MODE_UNSET.equals(mode)) {
            throw new BizException("结算模式待核实,暂不能录正式单据。" + SettleModeService.UNSET_BANNER);
        }
        if (req.getPeriodEnd().isBefore(req.getPeriodStart())) {
            throw new BizException("结算区间止不能早于区间起");
        }
        Settlement s = new Settlement();
        s.setModeSnap(mode);
        s.setPeriodStart(req.getPeriodStart());
        s.setPeriodEnd(req.getPeriodEnd());
        s.setPlatformAmount(req.getPlatformAmount());
        if (SettleModeService.MODE_PLATFORM.equals(mode)) {
            if (req.getFeeAmount() == null || req.getFeeAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("平台手续费必填(可为 0):报表要单列「平台这个月吃掉了我多少钱」");
            }
            if (req.getActualAmount() == null || req.getActualAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("实际到账金额必填:以银行/微信账单为准");
            }
            if (req.getAccountId() == null) {
                throw new BizException("入账账户必填:钱到了哪个账户");
            }
            Account account = accountMapper.selectById(req.getAccountId());
            if (account == null) {
                throw new BizException("入账账户不存在:id=" + req.getAccountId());
            }
            if (Boolean.TRUE.equals(account.getIsVirtual())) {
                throw new BizException("入账账户必须是真实账户(微信/支付宝/银行卡/现金):「"
                        + account.getAccountName() + "」是虚拟账户");
            }
            if (account.getStatus() != null && account.getStatus() == 0) {
                throw new BizException("入账账户「" + account.getAccountName() + "」已停用");
            }
            s.setFeeAmount(req.getFeeAmount());
            s.setActualAmount(req.getActualAmount());
            s.setAccountId(req.getAccountId());
            s.setStmtNo(nextNo("PT-"));
        } else {
            // DIRECT 商户账单核对单:手续费/到账/账户不适用(不动钱)
            s.setFeeAmount(BigDecimal.ZERO);
            s.setActualAmount(BigDecimal.ZERO);
            s.setStmtNo(nextNo("HD-"));
        }
        s.setSystemAmount(BigDecimal.ZERO);
        s.setDiffSales(BigDecimal.ZERO);
        s.setDiffArrival(BigDecimal.ZERO);
        s.setStlStatus(Settlement.ST_PENDING);
        s.setCreateUser(userId);
        settlementMapper.insert(s);
        opLogService.record(operator, SettleModeService.MODE_PLATFORM.equals(mode) ? "录入平台结算单" : "录入商户账单核对单",
                "settlement", s.getId(), null,
                String.format("%s %s~%s 账单%s 手续费%s 到账%s", s.getStmtNo(), s.getPeriodStart(), s.getPeriodEnd(),
                        s.getPlatformAmount(), s.getFeeAmount(), s.getActualAmount()));
        return s.getId();
    }

    // ============================== 确认(双模式分流) ==============================

    @Transactional(rollbackFor = Exception.class)
    public SettlementDtos.ConfirmResult confirm(Long id, Long userId, String operator) {
        Settlement s = mustGet(id);
        String mode = settleModeService.currentMode();
        if (SettleModeService.MODE_UNSET.equals(mode)) {
            throw new BizException("结算模式待核实,不能确认。" + SettleModeService.UNSET_BANNER);
        }
        if (!mode.equals(s.getModeSnap())) {
            throw new BizException(String.format(
                    "单据[%s]是 %s 模式下录入的,当前模式已定型为 %s:请作废本单,按当前模式重录",
                    s.getStmtNo(), s.getModeSnap(), mode));
        }
        // 凭证硬门禁前置(不动任何状态就拦:§9.1 无凭证不能进已结算;PLATFORM 才要平台账单)
        if (SettleModeService.MODE_PLATFORM.equals(mode)
                && attachmentService.countOf(ATTACH_REF_TYPE, s.getId()) == 0) {
            throw new BizException(String.format(
                    "结算单[%s]没有平台账单凭证:请先上传平台账单截图(refType=%s)再确认——无凭证不能核销(§9.1)",
                    s.getStmtNo(), ATTACH_REF_TYPE));
        }
        // 条件更新抢占(待核对→核销中):防双击/并发双过账;失败=已被确认或正在确认
        int claimed = settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, id)
                .eq(Settlement::getStlStatus, Settlement.ST_PENDING)
                .set(Settlement::getStlStatus, Settlement.ST_CONFIRMING));
        if (claimed != 1) {
            throw new BizException(String.format("单据[%s]状态为[%s],仅[待核对]可确认(防双击/重复核销)",
                    s.getStmtNo(), s.getStlStatus()));
        }

        LocalDateTime start = s.getPeriodStart().atStartOfDay();
        LocalDateTime endEx = s.getPeriodEnd().plusDays(1).atStartOfDay();

        return SettleModeService.MODE_PLATFORM.equals(mode)
                ? confirmPlatform(s, start, endEx, userId, operator)
                : confirmDirect(s, start, endEx, userId, operator);
    }

    /** PLATFORM:快照 → 两差 → 回填 → 两笔流水(凭证门禁已在 confirm 入口前置) */
    private SettlementDtos.ConfirmResult confirmPlatform(Settlement s, LocalDateTime start, LocalDateTime endEx,
                                                         Long userId, String operator) {
        // ① 系统额快照 = 区间内待回填销售聚合(仅正常/退款,退款为负,P0-3)
        BigDecimal system = queryMapper.unsettledAmountIn(start, endEx);
        // ② 两差对账
        BigDecimal diffSales = system.subtract(s.getPlatformAmount());
        BigDecimal expectedArrival = s.getPlatformAmount().subtract(s.getFeeAmount());
        BigDecimal diffArrival = expectedArrival.subtract(s.getActualAmount());
        boolean salesOk = withinThreshold(diffSales);
        boolean arrivalOk = withinThreshold(diffArrival);
        // ③ 回填(只碰 settlement_id IS NULL 行,幂等)
        int backfilled = queryMapper.backfill(s.getId(), start, endEx);
        // ④ 两笔流水:货款结算 收(毛额=到账+手续费,NON_PL)+ 平台手续费 支(利润表行);净入账=实际到账
        int flowCount = 0;
        MoneyPostingEvent event = new MoneyPostingEvent(REF_DOC_TYPE, s.getId(), userId);
        BigDecimal gross = s.getActualAmount().add(s.getFeeAmount());
        LocalDateTime bizTime = s.getPeriodEnd().atTime(23, 59, 59);
        if (gross.compareTo(BigDecimal.ZERO) > 0) {
            event.inflow(s.getAccountId(), gross, CashFlowCategory.SETTLE_TRANSFER, bizTime,
                    String.format("%s 平台结算到账(毛额=实际到账%s+手续费%s)", s.getStmtNo(), s.getActualAmount(), s.getFeeAmount()));
            flowCount++;
        }
        if (s.getFeeAmount().compareTo(BigDecimal.ZERO) > 0) {
            event.outflow(s.getAccountId(), s.getFeeAmount(), CashFlowCategory.PLATFORM_FEE, bizTime,
                    s.getStmtNo() + " 平台手续费");
            flowCount++;
        }
        if (flowCount > 0) {
            eventPublisher.publishEvent(event);   // 同步同事务:写手校验失败整体回滚
        }

        String finalStatus = (salesOk && arrivalOk) ? Settlement.ST_SETTLED : Settlement.ST_DIFF;
        String bizPeriod = s.getPeriodEnd().format(PERIOD);
        s.setSystemAmount(system);
        s.setDiffSales(diffSales);
        s.setDiffArrival(diffArrival);
        s.setStlStatus(finalStatus);
        s.setConfirmBy(operator);
        s.setConfirmAt(LocalDateTime.now());
        s.setBookPeriod(periodLockService.isLocked(bizPeriod) ? LocalDateTime.now().format(PERIOD) : bizPeriod);
        s.setUpdateUser(userId);
        settlementMapper.updateById(s);

        opLogService.record(operator, "平台结算单核销", "settlement", s.getId(), null, String.format(
                "%s 系统%s vs 账单%s → 漏单差%s%s;预计%s vs 到账%s → 扣款差%s%s;回填%d笔;流水%d条;→ %s",
                s.getStmtNo(), system, s.getPlatformAmount(), diffSales, salesOk ? "✓" : "⚠",
                expectedArrival, s.getActualAmount(), diffArrival, arrivalOk ? "✓" : "⚠",
                backfilled, flowCount, finalStatus));
        return result(s, backfilled, flowCount, salesOk, arrivalOk);
    }

    /** DIRECT:商户账单核对——只对差,不落收入流水不回填(钱已直连入账,防双记) */
    private SettlementDtos.ConfirmResult confirmDirect(Settlement s, LocalDateTime start, LocalDateTime endEx,
                                                       Long userId, String operator) {
        BigDecimal system = queryMapper.periodAmount(start, endEx);
        BigDecimal diffSales = system.subtract(s.getPlatformAmount());
        boolean salesOk = withinThreshold(diffSales);

        String finalStatus = salesOk ? Settlement.ST_CHECKED : Settlement.ST_DIFF;
        s.setSystemAmount(system);
        s.setDiffSales(diffSales);
        s.setDiffArrival(BigDecimal.ZERO);
        s.setStlStatus(finalStatus);
        s.setConfirmBy(operator);
        s.setConfirmAt(LocalDateTime.now());
        s.setBookPeriod(s.getPeriodEnd().format(PERIOD));
        s.setUpdateUser(userId);
        settlementMapper.updateById(s);

        opLogService.record(operator, "商户账单核对", "settlement", s.getId(), null, String.format(
                "%s 系统%s vs 商户账单%s → 差%s%s(直连模式只对差不动钱,到账核对走钱盘 M3-5)→ %s",
                s.getStmtNo(), system, s.getPlatformAmount(), diffSales, salesOk ? "✓" : "⚠", finalStatus));
        return result(s, 0, 0, salesOk, true);
    }

    // ============================== 差异复核 / 作废 ==============================

    /**
     * 差异挂起 → 收口(说明必填留痕):
     * M3-9 P1-5:PLATFORM 单收口时,差额不再只留一行字——按方向落一笔**非现金**流水
     * (account_id=NULL,只进利润表行,不动账户余额;真钱在确认时已按实际到账落准):
     *   蒸发额 = 漏单差 + 扣款差 = 已清出待结算的系统额 − 实际入账毛额;
     *   >0(平台多扣/吞货)→ 结算差异损失(杂费行);<0(多到账)→ 结算差异多收(其他收入-平台外)。
     * 条件更新抢占防双收口(双击会把差额流水落两遍)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resolveDiff(Long id, String note, Long userId, String operator) {
        if (note == null || note.trim().isEmpty()) {
            throw new BizException("差异复核说明必填:漏单差/扣款差核实成什么原因了(费率变化/漏单/吞货/额外扣款…)");
        }
        Settlement s = mustGet(id);
        if (!Settlement.ST_DIFF.equals(s.getStlStatus())) {
            throw new BizException(String.format("单据[%s]状态为[%s],仅[差异挂起]需要复核收口", s.getStmtNo(), s.getStlStatus()));
        }
        boolean platform = SettleModeService.MODE_PLATFORM.equals(s.getModeSnap());
        String finalStatus = platform ? Settlement.ST_SETTLED : Settlement.ST_CHECKED;
        // 条件更新抢占(防双收口双落差额流水,同 confirm 的 待核对→核销中 模式)
        int claimed = settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, id)
                .eq(Settlement::getStlStatus, Settlement.ST_DIFF)
                .set(Settlement::getStlStatus, finalStatus)
                .set(Settlement::getDiffNote, note.trim())
                .set(Settlement::getUpdateUser, userId));
        if (claimed != 1) {
            throw new BizException(String.format("单据[%s]已被他人收口(防重复收口),请刷新查看", s.getStmtNo()));
        }
        String flowNote = "";
        if (platform) {
            // 蒸发额 = diffSales + diffArrival = 系统额 −(实际到账+手续费):待结算清掉的钱与真钱入账的缺口
            BigDecimal vanish = nz(s.getDiffSales()).add(nz(s.getDiffArrival()));
            if (vanish.compareTo(BigDecimal.ZERO) != 0) {
                MoneyPostingEvent event = new MoneyPostingEvent(REF_DOC_TYPE, id, userId);
                String remark = String.format("结算差异收口 %s:漏单差%s 扣款差%s,核实说明:%s",
                        s.getStmtNo(), nz(s.getDiffSales()), nz(s.getDiffArrival()), note.trim());
                if (vanish.compareTo(BigDecimal.ZERO) > 0) {
                    event.pnlOutflow(vanish, CashFlowCategory.SETTLE_DIFF_LOSS, LocalDateTime.now(), remark);
                    flowNote = ";差额 " + vanish + " 落[结算差异损失](杂费行,非现金)";
                } else {
                    event.pnlInflow(vanish.negate(), CashFlowCategory.SETTLE_DIFF_GAIN, LocalDateTime.now(), remark);
                    flowNote = ";差额 " + vanish.negate() + " 落[结算差异多收](其他收入-平台外行,非现金)";
                }
                eventPublisher.publishEvent(event);
            }
        }
        opLogService.record(operator, "结算差异复核收口", "settlement", id, null, note.trim() + flowNote);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 作废(仅待核对;已核销的钱和回填都落了,逆向属红冲连锁范畴,M3 后续票) */
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id, Long userId, String operator) {
        Settlement s = mustGet(id);
        if (!Settlement.ST_PENDING.equals(s.getStlStatus())) {
            throw new BizException(String.format(
                    "单据[%s]状态为[%s],仅[待核对]可作废;已核销单的逆向要走红冲连锁(带回填与流水一起退)",
                    s.getStmtNo(), s.getStlStatus()));
        }
        s.setStlStatus(Settlement.ST_VOID);
        s.setUpdateUser(userId);
        settlementMapper.updateById(s);
        opLogService.record(operator, "作废结算单", "settlement", id, s.getStmtNo(), null);
    }

    // ============================== 列表 ==============================

    public List<SettlementDtos.BillRow> list(String status) {
        List<Settlement> bills = settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(status != null && !status.isEmpty(), Settlement::getStlStatus, status)
                .orderByDesc(Settlement::getId));
        Map<Long, String> accountNames = new HashMap<>();
        for (Account a : accountMapper.selectList(null)) {
            accountNames.put(a.getId(), a.getAccountName());
        }
        List<SettlementDtos.BillRow> rows = new ArrayList<>();
        for (Settlement s : bills) {
            SettlementDtos.BillRow row = new SettlementDtos.BillRow();
            row.setId(s.getId());
            row.setStmtNo(s.getStmtNo());
            row.setModeSnap(s.getModeSnap());
            row.setPeriodStart(s.getPeriodStart());
            row.setPeriodEnd(s.getPeriodEnd());
            row.setPlatformAmount(s.getPlatformAmount());
            row.setFeeAmount(s.getFeeAmount());
            row.setActualAmount(s.getActualAmount());
            row.setSystemAmount(s.getSystemAmount());
            row.setDiffSales(s.getDiffSales());
            row.setDiffArrival(s.getDiffArrival());
            boolean confirmed = s.getConfirmAt() != null;
            row.setSalesDiffOk(confirmed ? withinThreshold(s.getDiffSales()) : null);
            row.setArrivalDiffOk(confirmed ? withinThreshold(s.getDiffArrival()) : null);
            row.setAccountId(s.getAccountId());
            row.setAccountName(s.getAccountId() == null ? null : accountNames.get(s.getAccountId()));
            row.setStlStatus(s.getStlStatus());
            row.setConfirmBy(s.getConfirmBy());
            row.setConfirmAt(s.getConfirmAt());
            row.setDiffNote(s.getDiffNote());
            row.setBookPeriod(s.getBookPeriod());
            // 凭证数(前端"没传凭证先别点确认"软提示;后端 confirm 仍有硬门禁,双保险)
            row.setBackfillCount(null);
            row.setAttachmentCount(attachmentService.countOf(ATTACH_REF_TYPE, s.getId()));
            rows.add(row);
        }
        return rows;
    }

    // ============================== 兑换活动 ROI(Scenario04 对冲闭环) ==============================

    /**
     * 兑换出货成本 vs 厂家补贴确认额(deduction 非作废;抵扣确认单即"确认"载体):
     * 补贴到账对冲兑换成本——deduction 在 M3-2 用于货款抵扣,这里补它的经营视角。
     * from/to 只筛兑换销售侧(deduction 的 period_desc 是自由文本,不参与日期筛)。
     */
    public SettlementDtos.ExchangeRoiResp exchangeRoi(LocalDate from, LocalDate to) {
        LocalDateTime start = from == null ? null : from.atStartOfDay();
        LocalDateTime endEx = to == null ? null : to.plusDays(1).atStartOfDay();
        SettlementDtos.ExchangeRoiResp resp = new SettlementDtos.ExchangeRoiResp();
        resp.setExchangeCost(queryMapper.exchangeCost(start, endEx));
        resp.setExchangeQty(queryMapper.exchangeQty(start, endEx));
        resp.setCostMissingCount(queryMapper.exchangeCostMissing(start, endEx));
        BigDecimal used = queryMapper.deductionTotalByStatus("已抵扣");
        BigDecimal pending = queryMapper.deductionTotalByStatus("待抵扣");
        resp.setSubsidyUsed(used);
        resp.setSubsidyPending(pending);
        resp.setSubsidyConfirmed(used.add(pending));
        resp.setNet(resp.getSubsidyConfirmed().subtract(resp.getExchangeCost()));
        return resp;
    }

    // ============================== 工具 ==============================

    private SettlementDtos.ConfirmResult result(Settlement s, int backfilled, int flowCount,
                                                boolean salesOk, boolean arrivalOk) {
        SettlementDtos.ConfirmResult r = new SettlementDtos.ConfirmResult();
        r.setBillId(s.getId());
        r.setMode(s.getModeSnap());
        r.setSystemAmount(s.getSystemAmount());
        r.setDiffSales(s.getDiffSales());
        r.setDiffArrival(s.getDiffArrival());
        r.setSalesDiffOk(salesOk);
        r.setArrivalDiffOk(arrivalOk);
        r.setStlStatus(s.getStlStatus());
        r.setBackfillCount(backfilled);
        r.setFlowCount(flowCount);
        return r;
    }

    private boolean withinThreshold(BigDecimal diff) {
        return diff != null && diff.abs().compareTo(DIFF_THRESHOLD) <= 0;
    }

    private Settlement mustGet(Long id) {
        Settlement s = settlementMapper.selectById(id);
        if (s == null) {
            throw new BizException("结算单不存在:id=" + id);
        }
        return s;
    }

    private String nextNo(String prefix) {
        String like = prefix + LocalDate.now().format(DAY) + "-";
        Long count = settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .likeRight(Settlement::getStmtNo, like));
        return like + String.format("%03d", count + 1);
    }
}
