package top.aole.vend.regression;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.report.service.CostEngine;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景5:故障线下卖(审计结论:卡——钱通;货和销量与"机器账后台权威"冲突未定义;
 * P2-13 修复:线下销售复合单 = sale_record(线下补录,不入待结算)+ cash_flow(平台外收入)+ 盘点豁免)。
 *
 * M1 已落地:order_type=线下补录 口径守卫(不入销售额/不扣机器账)+ offline_flag /
 *   stocktake_item.offline_exempt 字段通道 + OFFLINE- 前缀单号约定。
 * M3 待接:复合单一次录入(同时生成 cash_flow 其他收入-平台外)。
 */
class Scenario05OfflineSaleTest extends RegressionSupport {

    @Autowired
    private CostEngine costEngine;

    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);

    @Test
    @DisplayName("口径守卫:线下补录收入不入销售额口径(钱走平台外收入行,M3),销售额只剩机器正常单")
    void offlineSaleExcludedFromSalesAmount() {
        Machine m = machine("故障机");
        Product p = product("线下卖的和其正", null, null);
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                DAY.minusDays(1), false, DAY.minusDays(1).atTime(8, 0), new Object[]{p.getId(), "10", "2.0"});
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                DAY, false, DAY.atTime(9, 0), new Object[]{p.getId(), "8", "2.0"});

        sale(m.getId(), p.getId(), "1", "5.0", "正常", DAY.atTime(10, 0));
        // 机器故障,微信直接转 13.2 线下卖 4 瓶(穿行原文数字)
        SaleRecord offline = sale(m.getId(), p.getId(), "4", "13.2", "线下补录", DAY.atTime(11, 0));
        offline.setOfflineFlag(true);
        offline.setOrderNo("OFFLINE-" + offline.getId());
        saleRecordMapper.updateById(offline);

        CostEngine.MonthAgg agg = costEngine.replay().getSkuMonth()
                .get(CostEngine.Replay.key(p.getId(), "2026-06"));
        assertEquals(0, agg.getSalesAmt().compareTo(new BigDecimal("5")),
                "销售额=正常5:线下13.2不入(它走 cash_flow 其他收入-平台外,M3),不污染待结算");
    }

    @Test
    @DisplayName("货账冲突定义:线下补录不扣机器账(机器账后台权威),差异留给盘点豁免承接")
    void offlineSaleDoesNotTouchMachineStock() {
        Machine m = machine("故障机2号");
        Product p = product("线下卖的可乐", null, null);
        stockWarehouse(p.getId(), "10");
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                DAY, false, DAY.atTime(9, 0), new Object[]{p.getId(), "6", "3.5"});

        sale(m.getId(), p.getId(), "1", "3.5", "正常", DAY.atTime(10, 0));
        sale(m.getId(), p.getId(), "4", "13.2", "线下补录", DAY.atTime(11, 0));

        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId())
                .compareTo(new BigDecimal("5")), "6−正常1=5:线下4瓶不扣机器账(后台没记录这4瓶出货)");
    }

    @Test
    @DisplayName("复合单字段通道在位:sale_record.offline_flag + stocktake_item.offline_exempt + cash_flow 平台外行注释")
    void offlineCompositeFieldsExist() {
        Map<String, Object> flag = assertColumn("yc_vend_sale_record", "offline_flag");
        assertEquals("NO", String.valueOf(flag.get("IS_NULLABLE")), "offline_flag NOT NULL 默认0");
        Map<String, Object> exempt = assertColumn("yc_vend_stocktake_item", "offline_exempt");
        assertEquals("NO", String.valueOf(exempt.get("IS_NULLABLE")), "盘点豁免标记在位(P2-13)");
        Map<String, Object> plLine = assertColumn("yc_vend_cash_flow", "pl_line");
        assertTrue(String.valueOf(plLine.get("COLUMN_COMMENT")).contains("平台外"),
                "利润表行映射含 其他收入-平台外");
        // 单号约定:线下补录自动生成 OFFLINE- 前缀(表注释锚定)
        Map<String, Object> orderNo = assertColumn("yc_vend_sale_record", "order_no");
        assertTrue(String.valueOf(orderNo.get("COLUMN_COMMENT")).contains("OFFLINE-"));
    }

    @Test
    @Disabled("M3-pending:线下销售复合单一次录入(sale_record+cash_flow 其他收入-平台外+豁免标记联动)依赖钱账模块;字段通道与两条口径守卫已在本类前三例验过")
    void offlineCompositeEntryGeneratesCashFlow() {
        // M3 实装后:一次录入 → 三件套同事务落地(13.2 进平台外收入,盘点差异豁免4瓶)
    }
}
