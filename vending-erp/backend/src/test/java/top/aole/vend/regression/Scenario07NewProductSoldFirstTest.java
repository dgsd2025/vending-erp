package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.service.ReportService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景7:新商品后台先卖(审计结论:卡——无采购史成本口径缺失;负库存拦截与导入放行两处矛盾;
 * P1-8 修复:无采购史成本 NULL 毛利显"—"禁 0 加权;导入豁免拦截+"待补录采购"红灯,手工单不豁免)。
 *
 * M1 已落地并全测(端到端,导入真链路)。
 */
class Scenario07NewProductSoldFirstTest extends RegressionSupport {

    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private ReportService reportService;

    @Test
    @DisplayName("无采购史成本口径:导入出货 cost_amount=NULL(禁0),毛利报表该 SKU 显\"—\"(hasCost=false)不进合计")
    void noPurchaseHistoryCostNullMarginDash() throws Exception {
        Machine m = machine("新品先卖机");
        Product p = product("新品罗伯克咖啡", "RG690501", null);
        alias("RG690501", "罗伯克咖啡", p.getId());

        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"XP001", "罗伯克咖啡", "RG690501", 1, 7, m.getDeviceId(), 6.0, "正常订单", "微信", "2026-07-10 10:00:00"},
                {"XP002", "罗伯克咖啡", "RG690501", 2, 7, m.getDeviceId(), 12.0, "正常订单", "微信", "2026-07-10 11:00:00"},
        }));
        assertEquals(2, resp.getRowOk());

        List<SaleRecord> records = saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getImportBatchId, resp.getBatchId()));
        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(r -> r.getCostAmount() == null),
                "无采购史 → cost_amount=NULL,绝不落 0 参与加权(§13.2-6)");

        ReportDtos.GrossMarginResp margin = reportService.grossMargin("2026-07", "sku");
        ReportDtos.GrossMarginRow row = margin.getRows().stream()
                .filter(r -> Objects.equals(r.getKey(), p.getId())).findFirst().orElse(null);
        assertNotNull(row);
        assertFalse(row.isHasCost(), "毛利显\"—(成本待补)\"");
        assertNull(row.getGrossProfit(), "无成本不算毛利,不用0凑数");
        assertEquals(0, row.getSalesAmt().compareTo(new BigDecimal("18.00")), "销售额照常统计");
        assertTrue(margin.getNoCostCount() >= 1, "报表标出无成本 SKU 数");
    }

    @Test
    @DisplayName("导入豁免+红灯:仓库0直接导入补货记录 → 转移单放行(neg_stock_exempt=1)+ 待补录采购红灯−5")
    void importReplenishExemptWithRedFlag() throws Exception {
        Machine m = machine("新品先铺机");
        Product p = product("新品泡面", "RG690502", null);
        alias("RG690502", "新品泡面桶装", p.getId());

        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 7, "新品泡面桶装", "RG690502", 0, 5, 5, "小邱", "2026-07-10 11:00:00"},
        }));
        assertEquals(1, resp.getDocsCreated(), "导入豁免拦截,单据照生成(两处矛盾已统一)");
        DocHead doc = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getImportBatchId, resp.getBatchId())).get(0);
        assertTrue(Boolean.TRUE.equals(doc.getNegStockExempt()), "导入转移单带负库存豁免标记");
        assertEquals(1, resp.getNegativeStock().size(), "亮\"待补录采购\"红灯");
        assertEquals(0, resp.getNegativeStock().get(0).getBalance().compareTo(new BigDecimal("-5")));
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("-5")),
                "仓库负库存如实记账,不藏");
    }

    @Test
    @DisplayName("手工单不豁免:仓库不足手工出库被拦;手工单请求豁免直接拒绝(豁免是导入通道特权)")
    void manualDocNotExempt() {
        Machine m = machine("手工试铺机");
        Product p = product("新品八宝粥", null, null);

        // 手工出库上架:无库存 → 负库存拦截(预挂单也要锁仓库侧)
        Long manualId = docService.createDoc(req(DocType.TRANSFER_OUT, m.getId(),
                DocService.SOURCE_MANUAL, LocalDate.now(), new Object[]{p.getId(), "5", "4.0"}), OP);
        docService.submit(manualId, OP);
        BizException blocked = assertThrows(BizException.class,
                () -> docService.confirm(manualId, OP, false, null));
        assertTrue(blocked.getMessage().contains("负库存拦截"), blocked.getMessage());

        // 手工单硬要豁免 → 拒绝
        BizException exemptDenied = assertThrows(BizException.class,
                () -> docService.confirm(manualId, OP, true, null));
        assertTrue(exemptDenied.getMessage().contains("手工单据不允许豁免"), exemptDenied.getMessage());
    }

    @Test
    @DisplayName("补录采购后闭环:进货10@2.5 → 负库存回正,后续销售有成本、毛利正常计")
    void backfillPurchaseRestoresCostChain() throws Exception {
        Machine m = machine("新品闭环机");
        Product p = product("新品咖啡闭环", "RG690503", null);
        alias("RG690503", "新品咖啡闭环装", p.getId());
        // 先卖(7-10)后补录采购(7-11)
        importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 2, "新品咖啡闭环装", "RG690503", 0, 4, 4, "小邱", "2026-07-10 09:00:00"},
        }));
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                LocalDate.of(2026, 7, 11), false, LocalDate.of(2026, 7, 11).atTime(8, 0),
                new Object[]{p.getId(), "10", "2.5"});
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("6")),
                "−4 + 10 = 6:红灯消除");

        sale(m.getId(), p.getId(), "2", "12.0", "正常", LocalDate.of(2026, 7, 12).atTime(10, 0));
        ReportDtos.GrossMarginRow row = reportService.grossMargin("2026-07", "sku").getRows().stream()
                .filter(r -> Objects.equals(r.getKey(), p.getId())).findFirst().orElse(null);
        assertNotNull(row);
        assertTrue(row.isHasCost(), "补录采购后成本链恢复");
        assertEquals(0, row.getCostAmt().compareTo(new BigDecimal("5.00")), "2 × 2.5(负库存期入库从0重建金额池)");
        assertEquals(0, row.getGrossProfit().compareTo(new BigDecimal("7.00")));
    }

    @Test
    @DisplayName("参考成本兜底字段在位:product.ref_cost 注释锚定禁0加权口径")
    void refCostFieldReady() {
        Map<String, Object> refCost = assertColumn("yc_vend_product", "ref_cost");
        assertTrue(String.valueOf(refCost.get("COLUMN_COMMENT")).contains("禁止用0"),
                "ref_cost 仅展示兜底,不进加权(附录C)");
    }
}
