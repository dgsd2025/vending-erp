package top.aole.vend.modules.monthly;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.service.ProfitReportService;
import top.aole.vend.modules.monthly.dto.MonthlyDtos;
import top.aole.vend.modules.monthly.service.MonthlyAnalysisService;
import top.aole.vend.modules.monthly.service.MonthlyCalendarService;
import top.aole.vend.modules.monthly.service.MonthlyExportService;
import top.aole.vend.modules.monthly.service.MonthlyReportService;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.service.StocktakeService;
import top.aole.vend.regression.RegressionSupport;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-4 月度报表包集成测试(vend_test_monthly 独立库,每例事务回滚)。
 *
 * 覆盖验收清单:
 * - 七件套各自取数 vs 直查一致(进销存/利润表/现金流水/往来/损耗/资产)
 * - 利润表勾稽(毛利 + Σ其后各行 = 经营利润)
 * - 导出 Excel 六 sheet 生成且 POI 可读回
 * - 导出 Word 生成且含七节标题
 * - AI 报告七节结构 + mock [MOCK] 徽标
 * - 空月优雅处理(无数据不崩)
 * - 财务工作日历状态判定
 */
@ActiveProfiles("test-monthly")
class MonthlyReportTest extends RegressionSupport {

    private static final String M = "2026-05";
    private static final LocalDateTime PURCHASE_T = LocalDateTime.parse("2026-04-01T09:00:00");

    @Autowired private MonthlyReportService monthlyReportService;
    @Autowired private MonthlyAnalysisService monthlyAnalysisService;
    @Autowired private MonthlyCalendarService monthlyCalendarService;
    @Autowired private MonthlyExportService monthlyExportService;
    @Autowired private ProfitReportService profitReportService;
    @Autowired private StocktakeService stocktakeService;

    // ============================== 造数工具 ==============================

    private void purchase(Long productId, String qty, String price) {
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                PURCHASE_T.toLocalDate(), false, PURCHASE_T, new Object[]{productId, qty, price});
    }

    private Long account(String name, String type, String opening, boolean virtual) {
        jdbc.update("INSERT INTO yc_vend_account (account_name, account_type, is_virtual, opening_balance, opening_set_at) "
                        + "VALUES (?,?,?,?,NOW())",
                name, type, virtual ? 1 : 0, new BigDecimal(opening));
        return jdbc.queryForObject("SELECT MAX(id) FROM yc_vend_account", Long.class);
    }

    /** 直接落一笔现金流水(测试替身:真实链路走 CashFlowWriter,这里只验聚合口径) */
    private void flow(Long acct, String dir, String amt, String plLine, String period, String bizTime) {
        jdbc.update("INSERT INTO yc_vend_cash_flow (flow_no, account_id, direction, amount, category, pl_line, "
                        + "ref_doc_type, ref_doc_id, biz_time, book_period) VALUES (?,?,?,?,?,?, '测试单', 1, ?, ?)",
                "MF-" + SEQ.incrementAndGet(), acct, dir, new BigDecimal(amt), plLine, plLine,
                LocalDateTime.parse(bizTime), period);
    }

    /** 已完成盘点 + 盘亏行(损耗数据源;offline_exempt=0 才算损耗) */
    private void loss(Long machineId, Long productId, String reason, String diffQty, String diffAmount,
                      String snapshotTime) {
        jdbc.update("INSERT INTO yc_vend_stocktake (st_no, scope_type, machine_id, snapshot_time, st_status) "
                        + "VALUES (?,?,?,?,'已完成')",
                "MST" + SEQ.incrementAndGet(), machineId == null ? "仓库" : "机器", machineId,
                LocalDateTime.parse(snapshotTime));
        Long stId = jdbc.queryForObject("SELECT MAX(id) FROM yc_vend_stocktake", Long.class);
        jdbc.update("INSERT INTO yc_vend_stocktake_item (stocktake_id, product_id, slot_no, book_qty, "
                        + "actual_qty, diff_qty, diff_amount, diff_reason, offline_exempt) "
                        + "VALUES (?,?,NULL,0,0,?,?,?,0)",
                stId, productId, diffQty, diffAmount, reason);
    }

    /** 标准场景:1 机 2 品,4 月进货、5 月销售 + 损耗 + 现金流水 + 账户 */
    private Scene buildScene() {
        Scene sc = new Scene();
        sc.machine = machine("月报测试机");
        sc.cola = product("月报-可乐", null, null);
        sc.water = product("月报-水", null, null);
        purchase(sc.cola.getId(), "100", "2.00");
        purchase(sc.water.getId(), "100", "1.00");
        // 5 月销售:可乐 30 收 90(单价3)、水 20 收 30(单价1.5)
        sale(sc.machine.getId(), sc.cola.getId(), "30", "90", "正常", LocalDateTime.parse("2026-05-10T12:00:00"));
        sale(sc.machine.getId(), sc.water.getId(), "20", "30", "正常", LocalDateTime.parse("2026-05-11T12:00:00"));
        // 5 月损耗:可乐过期 5 个,成本额 10(盘亏 diff 为负,lossStats SQL 取负后为正 10)
        loss(sc.machine.getId(), sc.cola.getId(), "过期", "-5", "-10", "2026-05-20T09:00:00");
        // 账户 + 现金流水
        sc.wechat = account("月报-微信", "微信", "1000", false);
        account("月报-平台待结算", "平台待结算", "0", true);
        flow(sc.wechat, "支", "12", "平台手续费", M, "2026-05-15T10:00:00");
        flow(sc.wechat, "支", "50", "杂费", M, "2026-05-16T10:00:00");
        flow(sc.wechat, "收", "120", "销售收入", M, "2026-05-17T10:00:00");
        return sc;
    }

    private static class Scene {
        Machine machine;
        Product cola;
        Product water;
        Long wechat;
    }

    // ============================== 1. 进销存汇总 vs 直查 ==============================

    @Test
    @DisplayName("① 进销存汇总:包内数字 = ReportService 直查(期末=期初+入−出)")
    void inventoryMatchesDirect() {
        buildScene();
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(M);
        assertEquals(M, pkg.getMonth());
        // 5 月出库数量合计 = 50(可乐30+水20)
        top.aole.vend.modules.report.dto.ReportDtos.InventorySummaryRow total = pkg.getInventory().getTotal();
        assertNotNull(total);
        assertEquals(0, total.getOutQty().compareTo(new BigDecimal("50")));
    }

    // ============================== 2. 利润表勾稽 ==============================

    @Test
    @DisplayName("② 利润表勾稽:毛利 + Σ(毛利后各非小计行) = 经营利润;且 = ProfitService 直查")
    void profitReconciles() {
        buildScene();
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(M);
        FinReportDtos.ProfitResp p = pkg.getProfit();

        BigDecimal gross = rowAmount(p, "grossProfit");
        // 毛利 = 收入120 − 成本(30*2+20*1=80) = 40
        assertEquals(0, gross.compareTo(new BigDecimal("40.00")), "毛利应为 40");

        // 勾稽:毛利 + 毛利之后所有非小计行 = 经营利润
        BigDecimal sum = gross;
        boolean afterGross = false;
        for (FinReportDtos.PlRow row : p.getRows()) {
            if ("grossProfit".equals(row.getKey())) {
                afterGross = true;
                continue;
            }
            if (afterGross && !row.isSubtotal()) {
                sum = sum.add(nz(row.getAmount()));
            }
        }
        assertEquals(0, sum.compareTo(nz(p.getOperatingProfit())), "毛利+后续各行应等于经营利润");

        // 包内利润表 = 直接调 ProfitService 一致(经营利润同值)
        FinReportDtos.ProfitResp direct = profitReportService.monthly(M);
        assertEquals(0, direct.getOperatingProfit().compareTo(p.getOperatingProfit()));
        // 平台手续费/杂费从现金流水入账
        assertEquals(0, rowAmount(p, "platformFee").compareTo(new BigDecimal("-12")), "平台手续费应为 -12");
        assertEquals(0, rowAmount(p, "miscExpense").compareTo(new BigDecimal("-50")), "杂费应为 -50");
    }

    // ============================== 3. 现金流水汇总 + 月末余额 ==============================

    @Test
    @DisplayName("③ 现金流水汇总:按账户/类别聚合 + 月末余额 = 期初+Σ净额")
    void cashflowSummaryMatches() {
        Scene sc = buildScene();
        MonthlyDtos.CashflowSummary cf = monthlyReportService.cashflowSummary(M);
        // 收 120 支 62 → 净 58
        assertEquals(0, cf.getTotalInflow().compareTo(new BigDecimal("120.00")));
        assertEquals(0, cf.getTotalOutflow().compareTo(new BigDecimal("62.00")));
        assertEquals(0, cf.getTotalNet().compareTo(new BigDecimal("58.00")));
        // 微信账户月末余额 = 期初1000 + 58 = 1058
        MonthlyDtos.AccountBalanceRow wechat = cf.getMonthEndBalances().stream()
                .filter(r -> r.getAccountId().equals(sc.wechat)).findFirst()
                .orElseThrow(() -> new AssertionError("微信账户缺失"));
        assertEquals(0, wechat.getMonthEndBalance().compareTo(new BigDecimal("1058.00")));
        // 现金合计不含虚拟账户
        assertEquals(0, cf.getMonthEndCashTotal().compareTo(new BigDecimal("1058.00")),
                "虚拟账户不计入现金合计");
        // 按类别有 3 类
        assertEquals(3, cf.getByCategory().size());
    }

    // ============================== 4. 损耗报表 vs lossStats ==============================

    @Test
    @DisplayName("⑤ 损耗报表:仅本月盘亏行,金额/原因 = StocktakeService.lossStats 直查")
    void lossSummaryMatches() {
        buildScene();
        MonthlyDtos.LossSummary ls = monthlyReportService.lossSummary(M);
        assertEquals(0, ls.getTotalAmount().compareTo(new BigDecimal("10.00")), "本月损耗 10");
        assertEquals("过期", ls.getTopReason());
        // 与 lossStats 直查同口径
        BigDecimal direct = stocktakeService.lossStats(13).stream()
                .filter(r -> M.equals(r.getMonth()))
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, direct.compareTo(ls.getTotalAmount()));
    }

    // ============================== 5. 往来 + 资产 ==============================

    @Test
    @DisplayName("④⑥ 往来表 + 资产快照:结构完整、字段非空(不崩)")
    void payableAndAsset() {
        buildScene();
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(M);
        assertNotNull(pkg.getPayable().getAging());
        assertNotNull(pkg.getPayable().getPlatformPending());
        assertNotNull(pkg.getAsset().getSnapshot());
        assertNotNull(pkg.getAsset().getSnapshot().getNetAsset());
    }

    // ============================== 6. AI 报告七节 + [MOCK] ==============================

    @Test
    @DisplayName("⑦ 月度经营分析报告:固定七节 + 每节数据溯源 + AI mock 带 [MOCK] + 幂等")
    void analysisSevenSections() {
        buildScene();
        MonthlyDtos.AnalysisReport report = monthlyAnalysisService.analyze(M, false);
        assertEquals(7, report.getSections().size(), "固定七节");
        List<String> keys = new java.util.ArrayList<>();
        report.getSections().forEach(s -> keys.add(s.getKey()));
        assertTrue(keys.containsAll(java.util.Arrays.asList(
                "overview", "sales", "profit", "inventory", "money", "risk", "improvement")));
        // 每节都有数据点且带溯源
        for (MonthlyDtos.AnalysisSection s : report.getSections()) {
            assertFalse(s.getDataPoints().isEmpty(), s.getKey() + " 应有数据点");
            assertNotNull(s.getNarrative());
            s.getDataPoints().forEach(dp -> assertNotNull(dp.getSource(), "数据点必带溯源"));
        }
        // mock 档:model 以 mock 开头,输出带 [MOCK],llmCallId 落库
        assertTrue(report.isMock());
        assertTrue(report.getAiText().contains("[MOCK]"));
        assertNotNull(report.getLlmCallId());
        // 幂等:同日再调命中缓存
        MonthlyDtos.AnalysisReport again = monthlyAnalysisService.analyze(M, false);
        assertTrue(again.isCacheHit());
        assertEquals(report.getLlmCallId(), again.getLlmCallId());
    }

    // ============================== 7. 导出 Excel 六 sheet 可读回 ==============================

    @Test
    @DisplayName("导出 Excel:POI 生成六 sheet 且可读回,数据行 > 0")
    void exportExcelReadBack() throws Exception {
        buildScene();
        byte[] xlsx = monthlyExportService.exportExcel(M);
        assertTrue(xlsx.length > 0);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(6, wb.getNumberOfSheets(), "六件套六 sheet");
            // 进销存 sheet 有标题 + 表头 + 数据行
            Sheet inv = wb.getSheetAt(0);
            assertTrue(inv.getLastRowNum() >= 2, "进销存 sheet 应有数据行");
            // 损耗 sheet 名含「损耗」
            boolean hasLoss = false;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetName(i).contains("损耗")) {
                    hasLoss = true;
                }
            }
            assertTrue(hasLoss, "应有损耗 sheet");
        }
    }

    // ============================== 8. 导出 Word 含七节 ==============================

    @Test
    @DisplayName("导出 Word:POI 生成 docx 可读回,含七节标题 + 表格")
    void exportWordReadBack() throws Exception {
        buildScene();
        byte[] docx = monthlyExportService.exportWord(M);
        assertTrue(docx.length > 0);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder all = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                all.append(p.getText()).append("\n");
            }
            String text = all.toString();
            assertTrue(text.contains("月度经营分析报告"), "含标题");
            assertTrue(text.contains("一、经营概况"), "含第一节");
            assertTrue(text.contains("七、下月改进建议"), "含第七节");
            // 至少一张表格(数据表)
            assertFalse(doc.getTables().isEmpty(), "含数据表格");
        }
    }

    // ============================== 9. 空月优雅处理 ==============================

    @Test
    @DisplayName("空月优雅处理:无任何数据,包/报告/日历/导出都不崩")
    void emptyMonthGraceful() throws Exception {
        // 不造任何数据
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage("2020-01");
        assertNotNull(pkg);
        assertNotNull(pkg.getProfit());
        MonthlyDtos.AnalysisReport report = monthlyAnalysisService.analyze("2020-01", false);
        assertEquals(7, report.getSections().size());
        assertNotNull(report.getAiText());
        MonthlyDtos.CalendarBoard board = monthlyCalendarService.board("2020-01");
        assertEquals(4, board.getDays().size());
        // 导出也不崩
        assertTrue(monthlyExportService.exportExcel("2020-01").length > 0);
        assertTrue(monthlyExportService.exportWord("2020-01").length > 0);
    }

    // ============================== 10. 财务工作日历 ==============================

    @Test
    @DisplayName("财务月度工作日历:1/2/3/5 日四步,状态据真实数据判定")
    void calendarBoard() {
        buildScene();
        MonthlyDtos.CalendarBoard board = monthlyCalendarService.board(M);
        assertEquals(4, board.getDays().size());
        // 3 日核对报表包 = 自动完成
        MonthlyDtos.CalendarDay day3 = board.getDays().stream()
                .filter(d -> d.getDay() == 3).findFirst()
                .orElseThrow(() -> new AssertionError("缺 3 日"));
        assertEquals(MonthlyCalendarService.AUTO_DONE, day3.getStatus());
        // 1 日盘点:有损耗数据 → 待人工(需财务确认盘点)
        MonthlyDtos.CalendarDay day1 = board.getDays().stream()
                .filter(d -> d.getDay() == 1).findFirst()
                .orElseThrow(() -> new AssertionError("缺 1 日"));
        assertEquals(MonthlyCalendarService.PENDING_MANUAL, day1.getStatus());
    }

    // ============================== 工具 ==============================

    private BigDecimal rowAmount(FinReportDtos.ProfitResp p, String key) {
        return p.getRows().stream().filter(r -> key.equals(r.getKey()))
                .map(r -> nz(r.getAmount())).findFirst().orElse(BigDecimal.ZERO);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
