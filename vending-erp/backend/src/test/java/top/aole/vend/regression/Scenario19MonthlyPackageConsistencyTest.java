package top.aole.vend.regression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.bi.dto.BiDtos;
import top.aole.vend.modules.bi.service.BiService;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.service.AssetSnapshotService;
import top.aole.vend.modules.finreport.service.ProfitReportService;
import top.aole.vend.modules.monthly.dto.MonthlyDtos;
import top.aole.vend.modules.monthly.service.MonthlyReportService;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.service.ReportService;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M4-7 全链路场景19:月报包一致性(vend_test_regression 独立库,每例事务回滚)。
 *
 * 一份月度报表包(MonthlyReportService.buildPackage)里的每件数字,必须与
 * 各自后端服务"单独查"的结果分毫不差 —— 报表包只是聚合器,不得自己另算一套口径:
 *   ① 进销存件   = ReportService.inventorySummary 单独查(出库数量)
 *   ② 利润表件   = ProfitReportService.monthly 单独查(经营利润)+ §13.1 勾稽在包层面再验
 *   ③ 资产件     = AssetSnapshotService.current 单独查 + §13.1 净家底公式恒等在包层面再验
 *   ④ BI 交叉核  = BiService.matrix 机器维销售额 = 利润表销售收入(同一批销售的两条取数口)
 */
class Scenario19MonthlyPackageConsistencyTest extends RegressionSupport {

    private static final String M = "2026-05";
    private static final LocalDateTime PURCHASE_T = LocalDateTime.parse("2026-04-01T09:00:00");

    @Autowired private MonthlyReportService monthlyReportService;
    @Autowired private ProfitReportService profitReportService;
    @Autowired private ReportService reportService;
    @Autowired private AssetSnapshotService assetSnapshotService;
    @Autowired private BiService biService;

    // ============================== 造数工具(复用 M4-4 已验口径) ==============================

    private void purchase(Long productId, String qty, String price) {
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                PURCHASE_T.toLocalDate(), false, PURCHASE_T, new Object[]{productId, qty, price});
    }

    private Long account(String name, String type, String opening) {
        jdbc.update("INSERT INTO yc_vend_account (account_name, account_type, is_virtual, opening_balance, opening_set_at) "
                        + "VALUES (?,?,0,?,NOW())",
                name, type, new BigDecimal(opening));
        return jdbc.queryForObject("SELECT MAX(id) FROM yc_vend_account", Long.class);
    }

    private void flow(Long acct, String dir, String amt, String plLine, String bizTime) {
        jdbc.update("INSERT INTO yc_vend_cash_flow (flow_no, account_id, direction, amount, category, pl_line, "
                        + "ref_doc_type, ref_doc_id, biz_time, book_period) VALUES (?,?,?,?,?,?, '测试单', 1, ?, ?)",
                "S19-" + SEQ.incrementAndGet(), acct, dir, new BigDecimal(amt), plLine, plLine,
                LocalDateTime.parse(bizTime), M);
    }

    private void loss(Long machineId, Long productId, String reason, String diffQty, String diffAmount,
                      String snapshotTime) {
        jdbc.update("INSERT INTO yc_vend_stocktake (st_no, scope_type, machine_id, snapshot_time, st_status) "
                        + "VALUES (?,?,?,?,'已完成')",
                "S19ST" + SEQ.incrementAndGet(), machineId == null ? "仓库" : "机器", machineId,
                LocalDateTime.parse(snapshotTime));
        Long stId = jdbc.queryForObject("SELECT MAX(id) FROM yc_vend_stocktake", Long.class);
        jdbc.update("INSERT INTO yc_vend_stocktake_item (stocktake_id, product_id, slot_no, book_qty, "
                        + "actual_qty, diff_qty, diff_amount, diff_reason, offline_exempt) "
                        + "VALUES (?,?,NULL,0,0,?,?,?,0)",
                stId, productId, diffQty, diffAmount, reason);
    }

    private Machine buildScene() {
        Machine m = machine("场景19-月报机");
        Product colaP = product("场景19-可乐", null, null);
        Product waterP = product("场景19-水", null, null);
        purchase(colaP.getId(), "100", "2.00");
        purchase(waterP.getId(), "100", "1.00");
        // 5 月正常销售:可乐 30 收 90、水 20 收 30(全正常口径 → BI/利润取数应完全对齐)
        sale(m.getId(), colaP.getId(), "30", "90", "正常", LocalDateTime.parse("2026-05-10T12:00:00"));
        sale(m.getId(), waterP.getId(), "20", "30", "正常", LocalDateTime.parse("2026-05-11T12:00:00"));
        // 5 月损耗:可乐过期 5 个,成本额 10
        loss(m.getId(), colaP.getId(), "过期", "-5", "-10", "2026-05-20T09:00:00");
        // 现金 + 供应商应付(让净家底/经营利润非平凡)
        Long wechat = account("场景19-微信", "微信", "1000");
        account("场景19-现金", "现金", "50");
        flow(wechat, "支", "12", "平台手续费", "2026-05-15T10:00:00");
        flow(wechat, "支", "50", "杂费", "2026-05-16T10:00:00");
        jdbc.update("INSERT INTO yc_vend_supplier (supplier_code, supplier_name, opening_payable, coop_status) "
                + "VALUES (?,?,?, '合作中')", "S19GYS" + SEQ.incrementAndGet(), "场景19-陈老板", new BigDecimal("120"));
        return m;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @Test
    @DisplayName("月报包七件套 = 各服务单独查一致;利润表勾稽 + 资产净家底公式在包层面恒等;BI 机器销售额 = 利润销售收入")
    void packageConsistentWithStandaloneQueries() {
        buildScene();
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(M);
        assertEquals(M, pkg.getMonth());

        // ---- ① 进销存件 = ReportService 单独查 ----
        ReportDtos.InventorySummaryResp invStandalone = reportService.inventorySummary(M);
        assertEquals(0, pkg.getInventory().getTotal().getOutQty()
                        .compareTo(invStandalone.getTotal().getOutQty()),
                "包内进销存出库数量 = ReportService 单独查");
        assertEquals(0, pkg.getInventory().getTotal().getOutQty().compareTo(new BigDecimal("50")),
                "出库合计 = 可乐30 + 水20");

        // ---- ② 利润表件 = ProfitReportService 单独查 + §13.1 勾稽 ----
        FinReportDtos.ProfitResp profit = pkg.getProfit();
        FinReportDtos.ProfitResp profitStandalone = profitReportService.monthly(M);
        assertEquals(0, profit.getOperatingProfit().compareTo(profitStandalone.getOperatingProfit()),
                "包内经营利润 = ProfitReportService 单独查");

        BigDecimal gross = profit.getRows().stream().filter(r -> "grossProfit".equals(r.getKey()))
                .map(r -> nz(r.getAmount())).findFirst().orElse(BigDecimal.ZERO);
        assertEquals(0, gross.compareTo(new BigDecimal("40.00")), "毛利=收入120−成本80");
        // 勾稽:毛利 + 毛利之后所有非小计行 = 经营利润(在报表包层面再验一次)
        BigDecimal sum = gross;
        boolean afterGross = false;
        for (FinReportDtos.PlRow r : profit.getRows()) {
            if ("grossProfit".equals(r.getKey())) {
                afterGross = true;
                continue;
            }
            if (afterGross && !r.isSubtotal()) {
                sum = sum.add(nz(r.getAmount()));
            }
        }
        assertEquals(0, sum.compareTo(nz(profit.getOperatingProfit())),
                "§13.1 勾稽:毛利+后续各行=经营利润");

        // ---- ③ 资产件 = AssetSnapshotService 单独查 + §13.1 净家底公式恒等 ----
        FinReportDtos.AssetSnapshotResp asset = pkg.getAsset().getSnapshot();
        FinReportDtos.AssetSnapshotResp assetStandalone = assetSnapshotService.current();
        assertNotNull(asset.getNetAsset());
        assertEquals(0, asset.getNetAsset().compareTo(assetStandalone.getNetAsset()),
                "包内净家底 = AssetSnapshotService 单独查");
        // §13.1:库存+待结算+现金+索赔应收−应付 = 净家底(报表包层面再验)
        BigDecimal identity = nz(asset.getInventoryAmount())
                .add(nz(asset.getPlatformPending()))
                .add(nz(asset.getCashTotal()))
                .add(nz(asset.getClaimReceivable()))
                .subtract(nz(asset.getPayableTotal()));
        assertEquals(0, identity.compareTo(nz(asset.getNetAsset())),
                "净家底公式恒等:五分项合成 = netAsset");

        // ---- ④ BI 交叉核:机器维销售额合计 = 利润表销售收入(同批正常销售的两条取数口)----
        BiDtos.MatrixResp biMachine = biService.matrix(M, "machine");
        BigDecimal biSalesTotal = biMachine.getRows().stream()
                .map(r -> nz(r.getSalesAmt())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal salesIncome = profit.getRows().stream().filter(r -> "salesIncome".equals(r.getKey()))
                .map(r -> nz(r.getAmount())).findFirst().orElse(BigDecimal.ZERO);
        assertEquals(0, biSalesTotal.compareTo(salesIncome),
                "BI 机器维销售额合计 = 利润表销售收入(全正常口径应完全对齐)");
        assertEquals(0, salesIncome.compareTo(new BigDecimal("120.00")), "销售收入=90+30");
    }
}
