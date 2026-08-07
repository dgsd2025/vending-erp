package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.PriceLog;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.PriceLogMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.service.ReportService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景12:后台改价未登记(审计结论:卡——毛利按实收算的口径未写死;改价侦测缺失;
 * P2-9 修复:导入校验 sale_record.单价≠product.ref_price → 弹确认更新档案 + 写 price_log;
 * §13.1 定死:毛利=实收−加权成本,售价仅参考)。
 *
 * M1 已落地并全测(端到端,导入真链路)。
 */
class Scenario12PriceChangeDetectTest extends RegressionSupport {

    @Autowired
    private PriceLogMapper priceLogMapper;
    @Autowired
    private ReportService reportService;

    @Test
    @DisplayName("改价侦测→确认→留痕(端到端):档案3.5 实卖3.0 → 侦测1条 → 确认更新档案 + price_log(导入侦测)")
    void priceChangeDetectedConfirmedLogged() throws Exception {
        Machine m = machine("改价场景机");
        Product p = product("被改价的雪碧", "RG691201", "3.5");
        alias("RG691201", "雪碧改价装", p.getId());

        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"GJ001", "雪碧改价装", "RG691201", 1, 3, m.getDeviceId(), 3.0, "正常订单", "微信", "2026-07-15 10:00:00"},
                {"GJ002", "雪碧改价装", "RG691201", 2, 3, m.getDeviceId(), 6.0, "正常订单", "微信", "2026-07-15 11:00:00"},
        }));
        assertEquals(1, resp.getPriceChangeCount(), "侦测到1个 SKU 改价");

        List<ImportDtos.PriceChange> changes = importService.listPriceChanges(resp.getBatchId());
        assertEquals(1, changes.size());
        assertEquals(p.getId(), changes.get(0).getProductId());
        assertEquals(0, changes.get(0).getRefPrice().compareTo(new BigDecimal("3.5")));
        assertEquals(0, changes.get(0).getNewPrice().compareTo(new BigDecimal("3.0")));

        // 老板点确认 → 档案更新 + price_log(change_source=导入侦测,喂定价 PDCA)
        ImportDtos.PriceConfirmReq confirmReq = new ImportDtos.PriceConfirmReq();
        ImportDtos.PriceConfirmReq.Item item = new ImportDtos.PriceConfirmReq.Item();
        item.setProductId(p.getId());
        item.setNewPrice(new BigDecimal("3.0"));
        List<ImportDtos.PriceConfirmReq.Item> items = new ArrayList<>();
        items.add(item);
        confirmReq.setItems(items);
        assertEquals(1, importService.confirmPriceChanges(resp.getBatchId(), confirmReq, OPERATOR));

        assertEquals(0, productMapper.selectById(p.getId()).getRefPrice()
                .compareTo(new BigDecimal("3.0")), "档案参考价已更新");
        PriceLog log = priceLogMapper.selectOne(new LambdaQueryWrapper<PriceLog>()
                .eq(PriceLog::getImportBatchId, resp.getBatchId()));
        assertNotNull(log, "改价留痕落 price_log");
        assertEquals("导入侦测", log.getChangeSource());
        assertEquals(0, log.getOldPrice().compareTo(new BigDecimal("3.5")));
        assertEquals(0, log.getNewPrice().compareTo(new BigDecimal("3.0")));
    }

    @Test
    @DisplayName("毛利口径写死=实收(§13.1):后台改价没登记也不影响毛利——按实收3.0算,不按档案3.5")
    void grossMarginUsesActualReceivedNotRefPrice() throws Exception {
        Machine m = machine("实收口径机");
        Product p = product("口径可乐", "RG691202", "3.5");
        alias("RG691202", "可乐口径装", p.getId());
        // 成本垫底:2.0 进货(时间早于销售)
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                LocalDate.of(2026, 7, 1), false, LocalDate.of(2026, 7, 1).atTime(8, 0),
                new Object[]{p.getId(), "10", "2.0"});

        importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"SK001", "可乐口径装", "RG691202", 1, 4, m.getDeviceId(), 3.0, "正常订单", "微信", "2026-07-15 12:00:00"},
        }));

        ReportDtos.GrossMarginRow row = reportService.grossMargin("2026-07", "sku").getRows().stream()
                .filter(r -> Objects.equals(r.getKey(), p.getId())).findFirst().orElse(null);
        assertNotNull(row);
        assertEquals(0, row.getSalesAmt().compareTo(new BigDecimal("3.00")), "收入=实收3.0,档案3.5只是参考");
        assertEquals(0, row.getCostAmt().compareTo(new BigDecimal("2.00")));
        assertEquals(0, row.getGrossProfit().compareTo(new BigDecimal("1.00")), "毛利=实收−加权成本(定死)");
    }

    @Test
    @DisplayName("守卫:档案无参考价(NULL)不算改价;价一致不重复报;确认相同价零更新")
    void detectGuards() throws Exception {
        Machine m = machine("改价守卫机");
        Product noRef = product("没定价的新品", "RG691203", null);
        alias("RG691203", "新品无价装", noRef.getId());
        Product same = product("价没变的红牛", "RG691204", "6.0");
        alias("RG691204", "红牛同价装", same.getId());

        ImportDtos.CommitResp resp = importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"SG001", "新品无价装", "RG691203", 1, 1, m.getDeviceId(), 4.0, "正常订单", "微信", "2026-07-16 10:00:00"},
                {"SG002", "红牛同价装", "RG691204", 1, 2, m.getDeviceId(), 6.0, "正常订单", "微信", "2026-07-16 11:00:00"},
        }));
        assertEquals(0, resp.getPriceChangeCount(), "NULL 参考价/同价均不报改价");

        // 确认一个与档案相同的价 → 零更新零留痕
        ImportDtos.PriceConfirmReq confirmReq = new ImportDtos.PriceConfirmReq();
        ImportDtos.PriceConfirmReq.Item item = new ImportDtos.PriceConfirmReq.Item();
        item.setProductId(same.getId());
        item.setNewPrice(new BigDecimal("6.0"));
        List<ImportDtos.PriceConfirmReq.Item> items = new ArrayList<>();
        items.add(item);
        confirmReq.setItems(items);
        assertEquals(0, importService.confirmPriceChanges(resp.getBatchId(), confirmReq, OPERATOR));
        assertEquals(0, priceLogMapper.selectCount(new LambdaQueryWrapper<PriceLog>()
                .eq(PriceLog::getImportBatchId, resp.getBatchId())), "同价不写 price_log");
    }
}
