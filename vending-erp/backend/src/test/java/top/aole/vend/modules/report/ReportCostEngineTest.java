package top.aole.vend.modules.report;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.imports.dto.InitialDtos;
import top.aole.vend.modules.imports.service.InitialImportService;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.service.CostEngine;
import top.aole.vend.modules.report.service.ReportService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1-6 集成测试(vend_test_report 库):移动加权成本引擎(附录C)+ 毛利三口径 + 进销存 + 库存查询 + 期初向导。
 */
@ActiveProfiles("test-report")
class ReportCostEngineTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired
    private CostEngine costEngine;
    @Autowired
    private ReportService reportService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private MachineMapper machineMapper;
    @Autowired
    private StockLedgerMapper stockLedgerMapper;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private InitialImportService initialImportService;

    // ============================== 工具 ==============================

    private Product product(String prefix) {
        Product p = new Product();
        p.setSkuCode(prefix + SEQ.incrementAndGet());
        p.setProductName("测试商品" + p.getSkuCode());
        p.setUnit("件");
        p.setProductStatus("在售");
        productMapper.insert(p);
        return p;
    }

    private Machine machine() {
        Machine m = new Machine();
        long n = SEQ.incrementAndGet();
        m.setMachineCode("TM" + n);
        m.setMachineName("测试机" + n);
        m.setDeviceId("DEV" + n);
        m.setMachineStatus("在线");
        machineMapper.insert(m);
        return m;
    }

    /** 采购入库单(带真实业务时间) */
    private Long purchase(Long productId, String qty, String price, LocalDateTime bizTime) {
        return confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                bizTime.toLocalDate(), false, bizTime, new Object[]{productId, qty, price});
    }

    /** 销售记录(带金额/类型/时间) */
    private void sale(Long machineId, Long productId, String qty, String amt, String type,
                      LocalDateTime bizTime) {
        SaleRecord s = new SaleRecord();
        s.setOrderNo("GM-" + SEQ.incrementAndGet());
        s.setMachineId(machineId);
        s.setProductId(productId);
        s.setQty(new BigDecimal(qty));
        s.setAmountReceived(new BigDecimal(amt));
        s.setOrderType(type);
        s.setBizTime(bizTime);
        s.setBizPeriod(bizTime.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        s.setBookPeriod(s.getBizPeriod());
        saleRecordMapper.insert(s);
    }

    private ReportDtos.GrossMarginRow skuRow(ReportDtos.GrossMarginResp resp, Long productId) {
        return resp.getRows().stream().filter(r -> Objects.equals(r.getKey(), productId))
                .findFirst().orElse(null);
    }

    private static LocalDateTime t(String text) {
        return LocalDateTime.parse(text);
    }

    // ============================== 1. 加权成本时序:多批不同价 → 出库结转 ==============================

    @Test
    void movingWeighted_multiBatchPrices_thenMonthlySlices() {
        Product p = product("MW");
        Machine m = machine();
        purchase(p.getId(), "10", "3.0", t("2026-06-01T08:00:00"));
        sale(m.getId(), p.getId(), "5", "25", "正常", t("2026-06-10T12:00:00")); // 成本 5×3=15
        purchase(p.getId(), "10", "4.0", t("2026-06-20T08:00:00"));            // 池 (5×3+10×4)/15=3.6667
        sale(m.getId(), p.getId(), "6", "30", "正常", t("2026-07-05T12:00:00")); // 成本 6×3.6667=22

        ReportDtos.GrossMarginResp june = reportService.grossMargin("2026-06", "sku");
        ReportDtos.GrossMarginRow jr = skuRow(june, p.getId());
        assertThat(jr).isNotNull();
        assertThat(jr.getSalesAmt()).isEqualByComparingTo("25.00");
        assertThat(jr.getCostAmt()).isEqualByComparingTo("15.00");
        assertThat(jr.getGrossProfit()).isEqualByComparingTo("10.00");
        assertThat(jr.getMarginPct()).isEqualByComparingTo("40.00");

        ReportDtos.GrossMarginResp july = reportService.grossMargin("2026-07", "sku");
        ReportDtos.GrossMarginRow yr = skuRow(july, p.getId());
        assertThat(yr.getCostAmt()).isEqualByComparingTo("22.00");
        assertThat(yr.getGrossProfit()).isEqualByComparingTo("8.00");
        assertThat(july.getMonths()).contains("2026-06", "2026-07");
    }

    // ============================== 2. qty=0 成本调整行 → 单位成本变(附录C) ==============================

    @Test
    void costAdjust_qtyZeroRow_onlyMovesAmount_changesUnitCost() {
        Product p = product("CA");
        Machine m = machine();
        purchase(p.getId(), "10", "3.0", t("2026-06-01T08:00:00")); // 池 10 / 30
        // 模拟 M1-7 成本调整单过账产物:qty=0、amount=Δ 的调整流水(附录C 契约)
        Long adjustDoc = confirmedDoc(DocType.COST_ADJUST, null, DocService.SOURCE_MANUAL,
                LocalDate.of(2026, 6, 5), false, t("2026-06-05T09:00:00"),
                new Object[]{p.getId(), "1", "0"}); // 明细仅载体(引擎只看 ledger)
        StockLedger adjust = new StockLedger();
        adjust.setProductId(p.getId());
        adjust.setLocationType(StockLedger.LOC_WAREHOUSE);
        adjust.setDocId(adjustDoc);
        adjust.setChangeQty(BigDecimal.ZERO);
        adjust.setBalanceQty(new BigDecimal("10"));
        adjust.setAmount(new BigDecimal("5")); // Δ金额 +5 → 单位成本 35/10=3.5
        adjust.setBizTime(t("2026-06-05T09:00:00"));
        stockLedgerMapper.insert(adjust);

        sale(m.getId(), p.getId(), "2", "10", "正常", t("2026-06-10T12:00:00")); // 成本 2×3.5=7
        ReportDtos.GrossMarginRow row = skuRow(reportService.grossMargin("2026-06", "sku"), p.getId());
        assertThat(row.getCostAmt()).isEqualByComparingTo("7.00");

        CostEngine.Replay replay = costEngine.replay();
        assertThat(replay.getPools().get(p.getId()).currentAvg()).isEqualByComparingTo("3.5");
    }

    // ============================== 3. 无采购史 → NULL 成本、毛利显「—」 ==============================

    @Test
    void noPurchaseHistory_costNull_excludedFromTotalGross() {
        Product p = product("NC");
        Machine m = machine();
        sale(m.getId(), p.getId(), "3", "9", "正常", t("2026-07-01T10:00:00"));

        ReportDtos.GrossMarginResp resp = reportService.grossMargin("2026-07", "sku");
        ReportDtos.GrossMarginRow row = skuRow(resp, p.getId());
        assertThat(row.isHasCost()).isFalse();
        assertThat(row.getCostAmt()).isNull();
        assertThat(row.getGrossProfit()).isNull();
        assertThat(resp.getNoCostCount()).isGreaterThanOrEqualTo(1);
        // 销售额进合计,毛利不进(costedSalesAmt 不含该行)
        assertThat(resp.getTotalSalesAmt().subtract(resp.getCostedSalesAmt()))
                .isGreaterThanOrEqualTo(new BigDecimal("9.00"));

        CostEngine.Replay replay = costEngine.replay();
        assertThat(replay.getPools().get(p.getId()).hasCost()).isFalse();
        assertThat(replay.getPools().get(p.getId()).currentAvg()).isNull();
    }

    // ============================== 4. 毛利三口径:兑换0收入计成本 / 测试不计 / 退款负 ==============================

    @Test
    void threeScopes_exchangeZeroRevenue_testExcluded_refundNegative() {
        Product p = product("SC");
        Machine m = machine();
        purchase(p.getId(), "10", "2.0", t("2026-06-01T08:00:00"));
        sale(m.getId(), p.getId(), "2", "10", "正常", t("2026-06-02T10:00:00")); // 收10 成本4
        sale(m.getId(), p.getId(), "1", "3", "兑换", t("2026-06-03T10:00:00"));  // 收0 成本2(穿行场景4)
        sale(m.getId(), p.getId(), "1", "3", "测试", t("2026-06-04T10:00:00"));  // 全不计
        sale(m.getId(), p.getId(), "1", "-5", "退款", t("2026-06-05T10:00:00")); // 收-5 成本-2(逆向回池)

        ReportDtos.GrossMarginRow row = skuRow(reportService.grossMargin("2026-06", "sku"), p.getId());
        assertThat(row.getSalesAmt()).isEqualByComparingTo("5.00");   // 10 - 5
        assertThat(row.getCostAmt()).isEqualByComparingTo("4.00");    // 4 + 2 - 2
        assertThat(row.getGrossProfit()).isEqualByComparingTo("1.00");
        assertThat(row.getSalesQty()).isEqualByComparingTo("2");      // 2 + 1 - 1(测试不计)

        // 退款回池:10 - 2 - 1 + 1 = 8 件在池
        CostEngine.Replay replay = costEngine.replay();
        assertThat(replay.getPools().get(p.getId()).getQty()).isEqualByComparingTo("8");
    }

    // ============================== 5. 机器维度聚合 ==============================

    @Test
    void machineDimension_aggregatesPerMachine() {
        Product p = product("MD");
        Machine m1 = machine();
        Machine m2 = machine();
        purchase(p.getId(), "10", "1.0", t("2026-06-01T08:00:00"));
        sale(m1.getId(), p.getId(), "2", "6", "正常", t("2026-06-02T10:00:00"));
        sale(m2.getId(), p.getId(), "3", "9", "正常", t("2026-06-03T10:00:00"));

        ReportDtos.GrossMarginResp resp = reportService.grossMargin("2026-06", "machine");
        ReportDtos.GrossMarginRow r1 = resp.getRows().stream()
                .filter(r -> Objects.equals(r.getKey(), m1.getId())).findFirst().orElse(null);
        ReportDtos.GrossMarginRow r2 = resp.getRows().stream()
                .filter(r -> Objects.equals(r.getKey(), m2.getId())).findFirst().orElse(null);
        assertThat(r1).isNotNull();
        assertThat(r1.getSalesAmt()).isEqualByComparingTo("6.00");
        assertThat(r1.getCostAmt()).isEqualByComparingTo("2.00");
        assertThat(r2.getSalesAmt()).isEqualByComparingTo("9.00");
        assertThat(r2.getCostAmt()).isEqualByComparingTo("3.00");
    }

    // ============================== 6. 进销存汇总:期初/入库/出库/期末连续结转 ==============================

    @Test
    void inventorySummary_openingClosingCarryAcrossMonths() {
        Product p = product("IV");
        Machine m = machine();
        purchase(p.getId(), "10", "3.0", t("2026-06-01T08:00:00"));
        sale(m.getId(), p.getId(), "4", "20", "正常", t("2026-06-10T12:00:00"));
        sale(m.getId(), p.getId(), "2", "10", "正常", t("2026-07-08T12:00:00"));

        ReportDtos.InventorySummaryResp june = reportService.inventorySummary("2026-06");
        ReportDtos.InventorySummaryRow jr = june.getRows().stream()
                .filter(r -> Objects.equals(r.getProductId(), p.getId())).findFirst().orElse(null);
        assertThat(jr).isNotNull();
        assertThat(jr.getOpeningQty()).isEqualByComparingTo("0");
        assertThat(jr.getInQty()).isEqualByComparingTo("10");
        assertThat(jr.getInAmt()).isEqualByComparingTo("30.00");
        assertThat(jr.getOutQty()).isEqualByComparingTo("4");
        assertThat(jr.getOutAmt()).isEqualByComparingTo("12.00");
        assertThat(jr.getClosingQty()).isEqualByComparingTo("6");
        assertThat(jr.getClosingAmt()).isEqualByComparingTo("18.00");

        ReportDtos.InventorySummaryRow yr = reportService.inventorySummary("2026-07").getRows().stream()
                .filter(r -> Objects.equals(r.getProductId(), p.getId())).findFirst().orElse(null);
        // 期末上月 = 期初下月(连续结转)
        assertThat(yr.getOpeningQty()).isEqualByComparingTo("6");
        assertThat(yr.getOpeningAmt()).isEqualByComparingTo("18.00");
        assertThat(yr.getOutQty()).isEqualByComparingTo("2");
        assertThat(yr.getClosingQty()).isEqualByComparingTo("4");
        assertThat(yr.getClosingAmt()).isEqualByComparingTo("12.00");
    }

    // ============================== 7. 库存查询:仓库/机器两级 + 负库存红灯 ==============================

    @Test
    void stockQuery_warehouseMachineTwoLevels_negativeRedLight() {
        Product p = product("SQ");
        Machine m = machine();
        purchase(p.getId(), "5", "2.0", t("2026-06-01T08:00:00"));
        // 导入来源转移单:仓库 5-8=-3(豁免放行 → 负库存红灯),机器 +8
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.of(2026, 6, 2), true, t("2026-06-02T09:00:00"),
                new Object[]{p.getId(), "8", "2.0"});
        sale(m.getId(), p.getId(), "2", "6", "正常", t("2026-06-03T10:00:00")); // 机器 8-2=6

        ReportDtos.StockResp resp = reportService.stock();
        ReportDtos.StockRow row = resp.getRows().stream()
                .filter(r -> Objects.equals(r.getProductId(), p.getId())).findFirst().orElse(null);
        assertThat(row).isNotNull();
        assertThat(row.getWarehouseQty()).isEqualByComparingTo("-3");
        assertThat(row.getMachineQty().get(m.getId())).isEqualByComparingTo("6");
        assertThat(row.getTotalQty()).isEqualByComparingTo("3");
        assertThat(row.isNegative()).isTrue();
        assertThat(resp.getNegativeCount()).isGreaterThanOrEqualTo(1);
        assertThat(row.getUnitCost()).isEqualByComparingTo("2.0000");
        assertThat(resp.getDataAsOf()).isNotNull();
    }

    // ============================== 8. 转移不扰动全局成本池 ==============================

    @Test
    void transfer_doesNotDistortGlobalCostPool() {
        Product p = product("TR");
        Machine m = machine();
        purchase(p.getId(), "10", "3.0", t("2026-06-01T08:00:00"));
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.of(2026, 6, 2), true, t("2026-06-02T09:00:00"),
                new Object[]{p.getId(), "5", "3.0"});
        sale(m.getId(), p.getId(), "3", "15", "正常", t("2026-06-03T10:00:00"));

        ReportDtos.GrossMarginRow row = skuRow(reportService.grossMargin("2026-06", "sku"), p.getId());
        assertThat(row.getCostAmt()).isEqualByComparingTo("9.00"); // 3×3,转移无感
        CostEngine.Replay replay = costEngine.replay();
        assertThat(replay.getPools().get(p.getId()).getQty()).isEqualByComparingTo("7"); // 10-3(全局口径)
        assertThat(replay.getPools().get(p.getId()).currentAvg()).isEqualByComparingTo("3");
    }

    // ============================== 9. 负库存沿用最近均价 + 入库重建金额池(对齐冲刺0) ==============================

    @Test
    void negativeStock_usesLastAvg_thenPurchaseRebuildsPool() {
        Product p = product("NG");
        Machine m = machine();
        purchase(p.getId(), "5", "3.0", t("2026-06-01T08:00:00"));
        sale(m.getId(), p.getId(), "8", "40", "正常", t("2026-06-10T12:00:00")); // 超卖:成本 8×3=24(沿用均价)
        ReportDtos.GrossMarginRow row = skuRow(reportService.grossMargin("2026-06", "sku"), p.getId());
        assertThat(row.getCostAmt()).isEqualByComparingTo("24.00");

        purchase(p.getId(), "10", "4.0", t("2026-06-20T08:00:00")); // 池重建:qty -3+10=7,val=40 → 40/7
        sale(m.getId(), p.getId(), "1", "6", "正常", t("2026-06-25T12:00:00")); // 成本 5.7143
        ReportDtos.GrossMarginRow row2 = skuRow(reportService.grossMargin("2026-06", "sku"), p.getId());
        // 24 + 40/7 = 29.7143
        assertThat(row2.getCostAmt()).isEqualByComparingTo("29.71");
    }

    // ============================== 10. 成本重算回写(sale_record + ledger 快照) ==============================

    @Test
    void recalc_persistsCostSnapshots() {
        Product p = product("RC");
        Machine m = machine();
        purchase(p.getId(), "10", "3.0", t("2026-06-01T08:00:00"));
        sale(m.getId(), p.getId(), "2", "10", "正常", t("2026-06-02T10:00:00"));
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.of(2026, 6, 3), true, t("2026-06-03T09:00:00"),
                new Object[]{p.getId(), "4"});

        ReportDtos.RecalcResp resp = reportService.recalc("测试");
        assertThat(resp.getSaleUpdated()).isGreaterThanOrEqualTo(1);
        assertThat(resp.getLedgerUpdated()).isGreaterThanOrEqualTo(1);

        SaleRecord updated = saleRecordMapper.selectList(null).stream()
                .filter(s -> Objects.equals(s.getProductId(), p.getId()))
                .max(Comparator.comparing(SaleRecord::getId)).orElse(null);
        assertThat(updated.getCostAmount()).isEqualByComparingTo("6.0000"); // 2×3
    }

    // ============================== 11-13. 期初向导三步 + 对平 ==============================

    @Test
    void wizard_step1_conflictMustResolve_splitCreatesLegacyCode() throws Exception {
        byte[] wb = buildInitialWorkbook();
        InitialDtos.Step1PreviewResp preview = initialImportService.step1Upload("套表.xlsx", wb);
        assertThat(preview.getConflicts()).hasSize(1);
        assertThat(preview.getConflicts().get(0).getCode()).isEqualTo("SP909");
        assertThat(preview.getConflicts().get(0).getSplitCodes()).containsExactly("SP909A", "SP909B");
        assertThat(preview.getAutoCreateCodes()).contains("SP968"); // 配比有、档案缺 → 自动补建

        // 冲突不给方案 → 整批不放行(编码冲突)
        InitialDtos.Step1ConfirmReq bad = new InitialDtos.Step1ConfirmReq();
        bad.setToken(preview.getToken());
        assertThatThrownBy(() -> initialImportService.step1Confirm(bad, "测试"))
                .isInstanceOf(BizException.class).hasMessageContaining("整批不放行");

        // 重新上传拿 token(confirm 失败已消费 token)→ 选拆分
        InitialDtos.Step1PreviewResp again = initialImportService.step1Upload("套表.xlsx", wb);
        InitialDtos.Step1ConfirmReq req = new InitialDtos.Step1ConfirmReq();
        req.setToken(again.getToken());
        InitialDtos.ConflictResolution rr = new InitialDtos.ConflictResolution();
        rr.setCode("SP909");
        rr.setMode("split");
        req.getResolutions().add(rr);
        InitialDtos.Step1Resp resp = initialImportService.step1Confirm(req, "测试");
        assertThat(resp.getSplitProducts()).isEqualTo(2);
        assertThat(resp.getMachineCreated()).isEqualTo(1);

        Product a = productMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                .eq(Product::getSkuCode, "SP909A"));
        assertThat(a).isNotNull();
        assertThat(a.getLegacyCode()).isEqualTo("SP909"); // 原码留痕
        assertThat(a.getProductName()).isEqualTo("健力宝橙味T");
    }

    @Test
    void wizard_step2_purchases_buildOpeningDocsAndCostHistory() throws Exception {
        byte[] wb = buildInitialWorkbook();
        step1Split(wb);

        InitialDtos.Step2PreviewResp preview = initialImportService.step2Upload("套表.xlsx", wb);
        assertThat(preview.getRowCount()).isEqualTo(3);
        assertThat(preview.getTotalAmt()).isEqualByComparingTo("275.00");
        assertThat(preview.getMissingProducts()).isEmpty();

        InitialDtos.Step2Resp resp = initialImportService.step2Confirm(preview.getToken(), "测试");
        assertThat(resp.getDocsCreated()).isEqualTo(2); // 两个入库日
        assertThat(resp.getTotalAmt()).isEqualByComparingTo("275.00");
        assertThat(resp.getSupplierCreated()).isEqualTo(1);

        List<DocHead> docs = docHeadMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocHead>()
                        .eq(DocHead::getDocType, DocType.OPENING));
        assertThat(docs).hasSize(2);

        // 加权成本历史已建立:SP901 全期 100@2.0 → 单位成本 2
        Product sp901 = bySku("SP901");
        CostEngine.Replay replay = costEngine.replay();
        assertThat(replay.getPools().get(sp901.getId()).currentAvg()).isEqualByComparingTo("2");
        // 拆分组:采购行「健力宝橙味T」路由到 SP909A(50 件 75 元 → 1.5)
        Product sp909a = bySku("SP909A");
        assertThat(replay.getPools().get(sp909a.getId()).currentAvg()).isEqualByComparingTo("1.5");
    }

    @Test
    void wizard_step3_salesReuseChannel1_thenValidatePass() throws Exception {
        byte[] wb = buildInitialWorkbook();
        step1Split(wb);
        InitialDtos.Step2PreviewResp p2 = initialImportService.step2Upload("套表.xlsx", wb);
        initialImportService.step2Confirm(p2.getToken(), "测试");

        InitialDtos.Step3PreviewResp p3 = initialImportService.step3Upload("套表.xlsx", wb);
        assertThat(p3.getRowCount()).isEqualTo(3);
        assertThat(p3.getTotalAmt()).isEqualByComparingTo("21.50");
        ImportDtos.CommitResp resp = initialImportService.step3Confirm(p3.getToken(), "测试");
        assertThat(resp.getRowOk()).isEqualTo(3);
        assertThat(resp.getRowFail()).isZero();
        assertThat(resp.getPendingBind()).isZero(); // 别名全命中(第①步已绑)

        InitialDtos.StatusResp status = initialImportService.status();
        assertThat(status.isAllStepsDone()).isTrue();

        InitialDtos.ValidateReq vr = new InitialDtos.ValidateReq();
        vr.setExpectedPurchase(new BigDecimal("275.00"));
        vr.setExpectedSale(new BigDecimal("21.50"));
        InitialDtos.ValidateResp vres = initialImportService.validate(vr, "测试");
        assertThat(vres.isPurchasePass()).isTrue();
        assertThat(vres.isSalePass()).isTrue();
        assertThat(vres.isPass()).isTrue(); // 期初完成

        // 毛利联动:SP901 卖 2 件 9 元,成本 2×2=4
        Product sp901 = bySku("SP901");
        ReportDtos.GrossMarginRow row = skuRow(reportService.grossMargin("2026-07", "sku"), sp901.getId());
        assertThat(row.getCostAmt()).isEqualByComparingTo("4.00");
    }

    // ============================== 向导测试工具 ==============================

    private void step1Split(byte[] wb) {
        InitialDtos.Step1PreviewResp preview = initialImportService.step1Upload("套表.xlsx", wb);
        InitialDtos.Step1ConfirmReq req = new InitialDtos.Step1ConfirmReq();
        req.setToken(preview.getToken());
        InitialDtos.ConflictResolution rr = new InitialDtos.ConflictResolution();
        rr.setCode("SP909");
        rr.setMode("split");
        req.getResolutions().add(rr);
        initialImportService.step1Confirm(req, "测试");
    }

    private Product bySku(String sku) {
        return productMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                        .eq(Product::getSkuCode, sku));
    }

    /**
     * 最小老 Excel 套表(与真实套表同构):
     * 商品档案:SP901 可乐;SP909 两行(健力宝T/美汁源T,一码多品冲突)
     * 配比底稿:可乐售名→SP901(主对);健力宝售/美汁源售→SP909;神秘新品→SP968 仅兜底对(档案缺失自动补建)
     * 销售明细:设备 D901;可乐售名×2 共9元、健力宝售×1 7.5元、神秘新品×1 5元(2026-07)
     * 采购入库:6/22 GYS9 SP901 100件200元;7/15 GYS9 SP909(健力宝橙味T)50件75元
     */
    private byte[] buildInitialWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet prod = wb.createSheet("商品档案");
            addRow(prod, "采购商品名称", "商品编码", "商品分类", "规格", "整件规格", "单位", "进货价", "销售价(元)", "毛利率", "保质期(天)");
            addRow(prod, "可乐T", "SP901", "饮料", null, "24瓶", "瓶", 2.0, 4.5, null, 180);
            addRow(prod, "健力宝橙味T", "SP909", "饮料", null, "15瓶", "瓶", 1.5, 4.0, null, 180);
            addRow(prod, "美汁源T", "SP909", "饮料", null, "15瓶", "瓶", 1.8, 4.0, null, 180);

            Sheet map = wb.createSheet("配比采购销售编码底稿");
            addRow(map, "销售商品名称", "出货数量", "商品金额(元)", "销售单价",
                    "归集采购商品名称", "归集采购商品编码", "归集采购商品名称", "归集采购商品编码");
            addRow(map, "可乐售名", 2, 9.0, 4.5, "可乐T", "SP901", "可乐T", "SP901");
            addRow(map, "健力宝售名", 1, 7.5, 7.5, "健力宝橙味T", "SP909", null, null);
            addRow(map, "美汁源售名", 0, 0, 0, "美汁源T", "SP909", null, null);
            addRow(map, "神秘新品售名", 1, 5.0, 5.0, null, null, "神秘新品T", "SP968");

            Sheet sales = wb.createSheet("销售明细表6-7月");
            addRow(sales, "商品名称", "分类", "条形码", "出货数量", "货道号", "设备ID", "设备名称",
                    "运营商", "商品金额", "订单号", "订单类型", "支付方式", "出货时间");
            addRow(sales, "可乐售名", "饮料", "6900001", 2, "A1", "D901", "9楼测试机",
                    "op", 9.0, "ORD-901", "正常订单", "微信", "2026-07-01 10:00:00");
            addRow(sales, "健力宝售名", "饮料", "6900002", 1, "A2", "D901", "9楼测试机",
                    "op", 7.5, "ORD-902", "正常订单", "微信", "2026-07-02 11:00:00");
            addRow(sales, "神秘新品售名", "食品", "6900003", 1, "A3", "D901", "9楼测试机",
                    "op", 5.0, "ORD-903", "正常订单", "微信", "2026-07-03 12:00:00");

            Sheet pur = wb.createSheet("采购入库表");
            addRow(pur, "入库日期", "入账月份", "供应商编码", "供应商名称", "商品编码", "采购商品名称",
                    "商品编码", "整件规格", "采购整件数", "单位", "采购数量", "进货单价(元)", "采购金额(元)", "备注");
            addRow(pur, "2026-06-22", "2026-07", "GYS9", "测试供应商", "SP901", "可乐T",
                    "SP901", "24瓶", 4, "瓶", 96, 2.0, 192.0, null);
            addRow(pur, "2026-06-22", "2026-07", "GYS9", "测试供应商", "SP901", "可乐T",
                    "SP901", "24瓶", 1, "瓶", 4, 2.0, 8.0, null);
            addRow(pur, "2026-07-15", "2026-07", "GYS9", "测试供应商", "SP909", "健力宝橙味T",
                    "SP909", "15瓶", 4, "瓶", 50, 1.5, 75.0, null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void addRow(Sheet sheet, Object... cells) {
        Row row = sheet.createRow(sheet.getLastRowNum() == -1 && sheet.getPhysicalNumberOfRows() == 0
                ? 0 : sheet.getLastRowNum() + 1);
        for (int i = 0; i < cells.length; i++) {
            Object v = cells[i];
            if (v == null) {
                continue;
            }
            if (v instanceof Number) {
                row.createCell(i).setCellValue(((Number) v).doubleValue());
            } else {
                row.createCell(i).setCellValue(v.toString());
            }
        }
    }
}
