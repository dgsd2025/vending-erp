package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.service.ProfitReportService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.doc.service.RedFlushService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景14:锁账后补导漏单(审计结论:断——锁账只管改单不管补导入,追改已归档报表无解法;
 * P0-2 修复:biz_period/book_period 分离;锁后补导 book_period=当月,旧报表永不重算,
 * "上期调整"行承接;锁账线之前单据禁改/禁红冲,老板越权+强制备注)。
 *
 * M1 已落地并全测(端到端,导入真链路 + 单据确认 + 上期调整聚合取数口)。
 * M3-6 已接:已核销结算单 lock_diff_note 提示(finreport 刷新通道)、简版利润表"上期调整"行。
 */
class Scenario14LockedPeriodBackfillTest extends RegressionSupport {

    @Autowired
    private PeriodLockService periodLockService;
    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private ProfitReportService profitReportService;

    private String month(int minus) {
        return YearMonth.now().minusMonths(minus).format(PERIOD_FMT);
    }

    @Test
    @DisplayName("锁后补导(端到端,修断点):锁上月→导入上月漏单 → biz_period=上月 / book_period=当月;未锁月两者一致")
    void backfillImportBooksToCurrentMonth() throws Exception {
        Machine m = machine("补导场景机");
        Product p = product("漏单和其正", "RG691401", null);
        alias("RG691401", "和其正补导装", p.getId());

        periodLockService.lock(month(1), OP, "上月报表已出");

        LocalDate lastMonthDay = YearMonth.now().minusMonths(1).atDay(18);
        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"BD001", "和其正补导装", "RG691401", 1, 5, m.getDeviceId(), 3.0, "正常订单", "微信",
                        lastMonthDay + " 12:00:00"},
                {"BD002", "和其正补导装", "RG691401", 2, 5, m.getDeviceId(), 6.0, "正常订单", "微信",
                        LocalDate.now() + " 09:00:00"},
        }));
        assertEquals(2, resp.getRowOk());

        List<SaleRecord> records = saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getImportBatchId, resp.getBatchId()));
        SaleRecord backfill = records.stream().filter(r -> "BD001".equals(r.getOrderNo())).findFirst().orElseThrow(IllegalStateException::new);
        assertEquals(month(1), backfill.getBizPeriod(), "业务归属月=上月(真实出货时间)");
        assertEquals(month(0), backfill.getBookPeriod(), "入账月=当月:旧报表永不重算,上期调整行承接");

        SaleRecord normal = records.stream().filter(r -> "BD002".equals(r.getOrderNo())).findFirst().orElseThrow(IllegalStateException::new);
        assertEquals(month(0), normal.getBizPeriod());
        assertEquals(month(0), normal.getBookPeriod(), "未锁月:业务月=入账月");

        // 旧报表不重算的落库保证:上月的 book_period 桶里没有这条补导记录
        Integer lastMonthBook = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yc_vend_sale_record WHERE order_no='BD001' AND book_period=?",
                Integer.class, month(1));
        assertEquals(0, lastMonthBook, "补导不落入已归档月份的入账桶");
    }

    @Test
    @DisplayName("锁账线前单据禁改/禁红冲:红冲拒绝并带提示;老板越权+强制备注放行,反向单入当月")
    void lockedDocBlockedUnlessBossOverride() {
        Product p = product("锁账里的采购", null, null);
        LocalDate lastMonthDay = YearMonth.now().minusMonths(1).atDay(15);
        Long originId = confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                lastMonthDay, false, null, new Object[]{p.getId(), "10", "3.5"});
        assertEquals(month(1), docHeadMapper.selectById(originId).getBookPeriod());

        periodLockService.lock(month(1), OP, "月报已出");

        BizException e = assertThrows(BizException.class,
                () -> redFlushService.execute(originId, OP, "录错了", false));
        assertTrue(e.getMessage().contains("已锁账"), "拒绝提示带锁账线:" + e.getMessage());

        // 老板越权必须带备注
        periodLockService.assertPeriodOperable(month(1), "红冲", true, "老板拍板");
        assertThrows(BizException.class,
                () -> periodLockService.assertPeriodOperable(month(1), "红冲", true, " "),
                "越权必须强制备注");

        assertThrows(BizException.class,
                () -> redFlushService.execute(originId, OP, "老板拍板:上月这单确实录错", true),
                "P1-2:bossOverride 不带老板角色头一律拒绝");
        Long redId = redFlushService.execute(originId, OP, "老板拍板:上月这单确实录错", true,
                top.aole.vend.modules.period.service.PeriodLockService.ROLE_BOSS);
        assertEquals(DocStatus.RED_FLUSHED, docHeadMapper.selectById(originId).getDocStatus());
        DocHead red = docHeadMapper.selectById(redId);
        assertEquals(month(0), red.getBookPeriod(), "锁账期红冲的反向单入当月(不动旧报表)");
        assertTrue(red.getRemark().contains("锁账期老板越权"));
    }

    @Test
    @DisplayName("锁后补录单据:业务月<锁账线的单据确认 → book_period=当月(单据侧同口径)")
    void backfillDocBooksToCurrentMonth() {
        Product p = product("补录的报损货", null, null);
        periodLockService.lock(month(1), OP, null);
        Long docId = confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                YearMonth.now().minusMonths(1).atDay(20), false, null, new Object[]{p.getId(), "5", "2"});
        assertEquals(month(0), docHeadMapper.selectById(docId).getBookPeriod());
    }

    @Test
    @DisplayName("上期调整聚合(报表取数口):当月入账桶里 biz≠book 的销售/单据分组正确,金额对得上")
    void priorAdjustAggregation() throws Exception {
        Machine m = machine("上期调整机");
        Product p = product("上期调整货", "RG691402", null);
        alias("RG691402", "调整货补导装", p.getId());
        periodLockService.lock(month(1), OP, null);

        // 补导两笔上月销售(通道1真链路,金额 3.0 + 6.0)
        LocalDate lastMonthDay = YearMonth.now().minusMonths(1).atDay(10);
        importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"PA001", "调整货补导装", "RG691402", 1, 5, m.getDeviceId(), 3.0, "正常订单", "微信",
                        lastMonthDay + " 10:00:00"},
                {"PA002", "调整货补导装", "RG691402", 2, 5, m.getDeviceId(), 6.0, "正常订单", "微信",
                        lastMonthDay + " 11:00:00"},
        }));
        // 一笔当月正常销售:不进上期调整
        sale(m.getId(), p.getId(), "1", "9.9", "正常", LocalDate.now().atTime(12, 0));
        // 一张补录单据(业务上月/入账当月)
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                YearMonth.now().minusMonths(1).atDay(3), false, null, new Object[]{p.getId(), "5", "2"});

        Map<String, Object> result = periodLockService.priorAdjust(month(0));
        assertEquals(2L, ((Number) result.get("saleRows")).longValue(), "销售侧2行补导");
        assertEquals(0, ((BigDecimal) result.get("saleAmount")).compareTo(new BigDecimal("9.00")),
                "上期调整金额=3+6(当月9.9不进)");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docLines = (List<Map<String, Object>>) result.get("docLines");
        assertTrue(docLines.stream().anyMatch(r -> month(1).equals(String.valueOf(r.get("bizPeriod")))
                        && "采购入库".equals(String.valueOf(r.get("docType")))),
                "单据侧补录也进上期调整聚合");
    }

    @Test
    @DisplayName("锁账管理面:当前月不可锁;解锁限老板+强制备注;解锁后重锁复活旧行不撞唯一键")
    void lockManagementGuards() {
        assertThrows(BizException.class, () -> periodLockService.lock(month(0), OP, null), "当前月不可锁");
        Long firstId = periodLockService.lock(month(1), OP, "第一次锁");
        assertThrows(BizException.class,
                () -> periodLockService.unlock(month(1), OP, "备注", "录单员"), "非老板不可解锁");
        periodLockService.unlock(month(1), OP, "要补一张单", PeriodLockService.ROLE_BOSS);
        assertNull(periodLockService.lockLine());
        Long secondId = periodLockService.lock(month(1), OP, "补完重新锁");
        assertEquals(firstId, secondId, "复活同一条锁记录(2026-08-06 真测踩雷防复发)");
    }

    @Test
    @DisplayName("已核销结算单只提示不改状态的通道字段在位:settle_bill.lock_diff_note")
    void settleBillLockDiffNoteFieldReady() {
        Map<String, Object> col = assertColumn("yc_vend_settle_bill", "lock_diff_note");
        assertTrue(String.valueOf(col.get("COLUMN_COMMENT")).contains("锁后新增差异"),
                "口径:已核销结算单遇锁后补导只展示提示,不改状态(P0-2)");
    }

    @Test
    @DisplayName("M3-6 转绿:锁后补导命中已核销结算单区间 → 写 lock_diff_note 提示、stl_status 不回退(P0-2 只提示不改状态)")
    void settledBillGetsLockDiffNote() throws Exception {
        Machine m = machine("差异提示机");
        Product p = product("差异提示旺仔", "RG691403", null);
        alias("RG691403", "旺仔差异装", p.getId());

        // 已核销平台结算单覆盖上月整月(直造行:核销服务是并行票 M3-3,本场景只验 P0-2 提示通道)
        java.time.LocalDate start = YearMonth.now().minusMonths(1).atDay(1);
        java.time.LocalDate end = YearMonth.now().minusMonths(1).atEndOfMonth();
        String stmtNo = "STL-S14-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO yc_vend_settlement (stmt_no, period_start, period_end, " +
                "platform_amount, actual_amount, stl_status) VALUES (?,?,?,?,?,'已核销')", stmtNo, start, end, 100, 98);
        Long stlId = jdbc.queryForObject("SELECT id FROM yc_vend_settlement WHERE stmt_no=?", Long.class, stmtNo);

        periodLockService.lock(month(1), OP, "月报已出,结算单已核销");
        // 锁后补导(通道1真链路):2 笔上月销售落进已核销区间,book_period=当月
        importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"LD001", "旺仔差异装", "RG691403", 1, 5, m.getDeviceId(), 3.0, "正常订单", "微信",
                        start.plusDays(9) + " 10:00:00"},
                {"LD002", "旺仔差异装", "RG691403", 2, 5, m.getDeviceId(), 6.0, "正常订单", "微信",
                        start.plusDays(10) + " 11:00:00"},
        }));

        profitReportService.refreshLockDiffNotes();
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT stl_status, lock_diff_note FROM yc_vend_settlement WHERE id=?", stlId);
        String note = String.valueOf(after.get("lock_diff_note"));
        assertTrue(note.contains("锁后新增差异") && note.contains("2 笔") && note.contains("9.00"),
                "lock_diff_note 带笔数/金额提示:" + note);
        assertEquals("已核销", after.get("stl_status"), "只提示不改状态:核销状态不回退(P0-2)");
        // 利润表响应携带该提示(前端渲染入口)
        assertTrue(profitReportService.monthly(month(0)).getLockDiffNotes().stream()
                .anyMatch(r -> r.getSettlementId().equals(stlId)), "利润表带 lock_diff_note 行");
    }

    @Test
    @DisplayName("M3-6 转绿:简版利润表\"上期调整\"行 = 锁后补导聚合(主表收入不含补导;金额与 priorAdjust 取数口吻合)")
    void profitReportPriorAdjustLine() throws Exception {
        Machine m = machine("利润表调整机");
        Product p = product("利润表调整货", "RG691404", null);
        alias("RG691404", "调整货利润装", p.getId());
        periodLockService.lock(month(1), OP, null);

        // 补导两笔上月销售(3.0 + 6.0)+ 一笔当月正常销售 9.9
        java.time.LocalDate lastMonthDay = YearMonth.now().minusMonths(1).atDay(10);
        importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"PL001", "调整货利润装", "RG691404", 1, 5, m.getDeviceId(), 3.0, "正常订单", "微信",
                        lastMonthDay + " 10:00:00"},
                {"PL002", "调整货利润装", "RG691404", 2, 5, m.getDeviceId(), 6.0, "正常订单", "微信",
                        lastMonthDay + " 11:00:00"},
        }));
        sale(m.getId(), p.getId(), "1", "9.9", "正常", LocalDate.now().atTime(12, 0));

        FinReportDtos.ProfitResp resp = profitReportService.monthly(month(0));
        BigDecimal salesIncome = plRow(resp, "salesIncome");
        BigDecimal priorAdjust = plRow(resp, "priorAdjust");
        assertEquals(0, salesIncome.compareTo(new BigDecimal("9.90")), "主表销售收入不含补导:" + salesIncome);
        assertEquals(0, priorAdjust.compareTo(new BigDecimal("9.00")),
                "上期调整行=补导收入 3+6(无采购史成本 0):" + priorAdjust);
        // 与 M1 取数口吻合(本类第4例验过聚合口本身)
        Map<String, Object> agg = periodLockService.priorAdjust(month(0));
        assertEquals(0, priorAdjust.compareTo((BigDecimal) agg.get("saleAmount")),
                "利润表上期调整行金额 = priorAdjust().saleAmount");
        // 上月入账桶不被补导污染(旧报表永不重算)
        assertEquals(0, plRow(profitReportService.monthly(month(1)), "salesIncome")
                .compareTo(BigDecimal.ZERO), "上月利润表无补导收入");
    }

    private BigDecimal plRow(FinReportDtos.ProfitResp resp, String key) {
        return resp.getRows().stream().filter(r -> key.equals(r.getKey())).findFirst()
                .orElseThrow(IllegalStateException::new).getAmount();
    }
}
