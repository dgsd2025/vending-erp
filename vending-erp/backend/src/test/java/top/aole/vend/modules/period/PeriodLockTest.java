package top.aole.vend.modules.period;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.doc.service.RedFlushService;
import top.aole.vend.modules.imports.mapper.ImportQueryMapper;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-7 验收组3:锁账×补导(审计 P0-2 / 穿行场景14)。
 * 覆盖:锁账线语义(某月及之前)/ 解锁守卫(老板+强制备注)/ 锁后改单红冲拒绝+老板越权 /
 * 补导 book_period=当前月(导入判定点+单据确认)/ 上期调整聚合。
 */
@ActiveProfiles("test-period")
class PeriodLockTest extends BaseIntegrationTest {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Long P = 703L;

    @Autowired
    private PeriodLockService periodLockService;
    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private ImportQueryMapper importQueryMapper;

    private String month(int minusMonths) {
        return YearMonth.now().minusMonths(minusMonths).format(PERIOD);
    }

    @Test
    @DisplayName("锁账线语义:锁上月=锁上月及之前;当前月不可锁;重复锁拒绝;解锁限老板+强制备注")
    void lockLineSemanticsAndUnlockGuards() {
        assertNull(periodLockService.lockLine());
        periodLockService.lock(month(1), OP, "月报已出");
        assertEquals(month(1), periodLockService.lockLine());
        assertTrue(periodLockService.isLocked(month(1)), "锁账月本身已锁");
        assertTrue(periodLockService.isLocked(month(3)), "更早月份同样在锁账线内");
        assertFalse(periodLockService.isLocked(month(0)), "当前月未锁");

        // 导入通道判定点(唯一改点):periodLocked = 存在 period ≥ 业务月 的锁记录
        assertTrue(importQueryMapper.periodLocked(month(3)) > 0, "导入判定:早于锁账线的业务月=已锁");
        assertTrue(importQueryMapper.periodLocked(month(1)) > 0);
        assertEquals(0, importQueryMapper.periodLocked(month(0)), "导入判定:当前月未锁");

        assertThrows(BizException.class, () -> periodLockService.lock(month(0), OP, null), "当前月不可锁");
        assertThrows(BizException.class, () -> periodLockService.lock(month(2), OP, null), "锁账线内重复锁拒绝");

        assertThrows(BizException.class,
                () -> periodLockService.unlock(month(1), OP, "备注", "录单员"), "非老板角色不可解锁");
        assertThrows(BizException.class,
                () -> periodLockService.unlock(month(1), OP, " ", PeriodLockService.ROLE_BOSS), "解锁强制备注");
        periodLockService.unlock(month(1), OP, "7月漏了一张报损要补", PeriodLockService.ROLE_BOSS);
        assertNull(periodLockService.lockLine(), "解锁后锁账线回落(无锁)");
    }

    @Test
    @DisplayName("防复发:解锁后重新锁同月 → 复活旧行不撞 uk_lock_period(2026-08-06 浏览器真测踩雷)")
    void relockAfterUnlockDoesNotHitUniqueKey() {
        Long firstId = periodLockService.lock(month(1), OP, "第一次锁");
        periodLockService.unlock(month(1), OP, "要补一张单", PeriodLockService.ROLE_BOSS);
        assertNull(periodLockService.lockLine());
        Long secondId = periodLockService.lock(month(1), OP, "补完重新锁");
        assertEquals(firstId, secondId, "复活同一条锁记录(逻辑删行占着唯一键)");
        assertEquals(month(1), periodLockService.lockLine(), "重新锁定生效");
    }

    @Test
    @DisplayName("锁账后改单/红冲拒绝(抛异常带提示);老板越权+强制备注放行(占位)")
    void lockedPeriodBlocksRedFlushUnlessBossOverride() {
        // 上月确认一张采购入库(锁之前确认 → book_period=上月)
        LocalDate lastMonthDay = YearMonth.now().minusMonths(1).atDay(15);
        Long originId = confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                lastMonthDay, false, null, new Object[]{P, "10", "3.5"});
        assertEquals(month(1), docHeadMapper.selectById(originId).getBookPeriod());

        periodLockService.lock(month(1), OP, "月报已出");

        BizException e = assertThrows(BizException.class,
                () -> redFlushService.execute(originId, OP, "录错了", false));
        assertTrue(e.getMessage().contains("已锁账"), "拒绝提示带锁账原因:" + e.getMessage());

        // 老板越权(占位)+强制备注 → 放行;红冲单入当月(旧报表永不重算)
        assertThrows(BizException.class,
                () -> redFlushService.execute(originId, OP, "老板拍板:上月这单确实录错", true, "录单员"),
                "P1-2:bossOverride 必须配老板角色头,非老板角色拒绝");
        Long redId = redFlushService.execute(originId, OP, "老板拍板:上月这单确实录错", true,
                PeriodLockService.ROLE_BOSS);
        assertEquals(DocStatus.RED_FLUSHED, docHeadMapper.selectById(originId).getDocStatus());
        DocHead red = docHeadMapper.selectById(redId);
        assertEquals(month(0), red.getBookPeriod(), "锁账期红冲的反向单入当月");
        assertTrue(red.getRemark().contains("锁账期老板越权"));
    }

    @Test
    @DisplayName("锁后补导:业务月<锁账线的单据确认 → book_period=当前月(上期调整承接)")
    void backfillDocBooksToCurrentMonth() {
        periodLockService.lock(month(1), OP, null);
        LocalDate lastMonthDay = YearMonth.now().minusMonths(1).atDay(20);
        Long docId = confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                lastMonthDay, false, null, new Object[]{P, "5", "2"});
        DocHead head = docHeadMapper.selectById(docId);
        assertEquals(month(0), head.getBookPeriod(), "业务月已锁 → 入账月=当前月");

        // 未锁月份正常:业务月=入账月
        Long normalId = confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{P, "5", "2"});
        assertEquals(month(0), docHeadMapper.selectById(normalId).getBookPeriod());
    }

    @Test
    @DisplayName("上期调整聚合:book_period 内 biz≠book 的销售按 业务月+批次 聚合,金额/行数正确")
    void priorAdjustAggregation() {
        // 两条补导销售:业务月=上月,入账=当月(模拟锁后补导落库口径)
        insertBackfillSale("10.00");
        insertBackfillSale("5.50");
        // 一条正常当月销售(不应进上期调整)
        insertSale(901L, P, "1", "正常", LocalDateTime.now());
        // 一张补导单据(业务上月/入账当月)
        periodLockService.lock(month(1), OP, null);
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                YearMonth.now().minusMonths(1).atDay(3), false, null, new Object[]{P, "5", "2"});

        Map<String, Object> result = periodLockService.priorAdjust(month(0));
        assertEquals(2L, ((Number) result.get("saleRows")).longValue(), "销售侧2行补导");
        assertEquals(0, ((BigDecimal) result.get("saleAmount")).compareTo(new BigDecimal("15.50")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> saleLines = (List<Map<String, Object>>) result.get("saleLines");
        assertEquals(month(1), String.valueOf(saleLines.get(0).get("bizPeriod")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docLines = (List<Map<String, Object>>) result.get("docLines");
        assertTrue(docLines.stream().anyMatch(m -> month(1).equals(String.valueOf(m.get("bizPeriod")))
                        && "采购入库".equals(String.valueOf(m.get("docType")))),
                "单据侧补导也进上期调整聚合");
    }

    /** 补导销售:biz_period=上月,book_period=当月 */
    private void insertBackfillSale(String amount) {
        SaleRecord s = new SaleRecord();
        s.setOrderNo("BF-" + cn.hutool.core.util.IdUtil.getSnowflakeNextIdStr());
        s.setMachineId(901L);
        s.setProductId(P);
        s.setQty(BigDecimal.ONE);
        s.setAmountReceived(new BigDecimal(amount));
        s.setOrderType("正常");
        s.setBizTime(YearMonth.now().minusMonths(1).atDay(10).atTime(12, 0));
        s.setBizPeriod(month(1));
        s.setBookPeriod(month(0));
        saleRecordMapper.insert(s);
    }
}
