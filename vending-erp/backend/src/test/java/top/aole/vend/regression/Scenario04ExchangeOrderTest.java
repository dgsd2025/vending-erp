package top.aole.vend.regression;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.report.service.CostEngine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景4:兑换出库(审计结论:错——订单类型未分流 → 污染待结算虚账+销售额,且可能双扣库存;
 * P0-3 三口径修复:①结算口径=仅正常(退款负) ②补货口径=正常+兑换 ③毛利:兑换收入0计成本)。
 */
class Scenario04ExchangeOrderTest extends RegressionSupport {

    @Autowired
    private CostEngine costEngine;

    private static final LocalDate DAY = LocalDate.of(2026, 6, 2);

    /** 垫底:仓库进货 10@2.0,导入源转移 8 上机 */
    private Object[] setup() {
        Machine m = machine("兑换场景机");
        Product p = product("兑换款和其正", null, null);
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                LocalDate.of(2026, 6, 1), false, LocalDate.of(2026, 6, 1).atTime(8, 0),
                new Object[]{p.getId(), "10", "2.0"});
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                DAY, false, DAY.atTime(9, 0), new Object[]{p.getId(), "8", "2.0"});
        return new Object[]{m, p};
    }

    @Test
    @DisplayName("三口径之毛利/销售额:兑换收入按0不入销售额(不污染),成本照计;退款负;测试全不计")
    void exchangeRevenueZeroButCostCounted() {
        Object[] ctx = setup();
        Machine m = (Machine) ctx[0];
        Product p = (Product) ctx[1];

        sale(m.getId(), p.getId(), "1", "5.0", "正常", DAY.atTime(10, 0));   // 收入5 成本2
        sale(m.getId(), p.getId(), "2", "7.0", "兑换", DAY.atTime(11, 0));   // 收入按0(哪怕导出带金额7) 成本4
        sale(m.getId(), p.getId(), "1", "-3.0", "退款", DAY.atTime(12, 0));  // 收入-3 成本-2(逆向回池)
        sale(m.getId(), p.getId(), "1", "99.0", "测试", DAY.atTime(13, 0));  // 全不计

        CostEngine.Replay replay = costEngine.replay();
        CostEngine.MonthAgg agg = replay.getSkuMonth().get(CostEngine.Replay.key(p.getId(), "2026-06"));
        assertNotNull(agg);
        assertEquals(0, agg.getSalesAmt().compareTo(new BigDecimal("2")),
                "销售额=5−3,兑换的7与测试的99都不进(不污染销售额/待结算)");
        assertEquals(0, agg.getCostAmt().compareTo(new BigDecimal("4")),
                "成本=正常2+兑换4−退款2:兑换真消耗库存,成本照计(由补贴到账对冲,M3)");
        assertEquals(0, agg.getSalesQty().compareTo(new BigDecimal("2")), "销量=1+2−1(测试不计)");
    }

    @Test
    @DisplayName("三口径之补货口径:机器库存出货=正常+兑换(真消耗),退款/测试/线下不扣机器账")
    void machineStockOutboundCountsNormalPlusExchange() {
        Object[] ctx = setup();
        Machine m = (Machine) ctx[0];
        Product p = (Product) ctx[1];

        sale(m.getId(), p.getId(), "1", "5.0", "正常", DAY.atTime(10, 0));
        sale(m.getId(), p.getId(), "2", "0", "兑换", DAY.atTime(11, 0));
        sale(m.getId(), p.getId(), "1", "-5.0", "退款", DAY.atTime(12, 0));
        sale(m.getId(), p.getId(), "5", "0", "测试", DAY.atTime(13, 0));

        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId())
                .compareTo(new BigDecimal("5")), "8 −(正常1+兑换2)=5:退款/测试不动机器账");
    }

    @Test
    @DisplayName("双扣防线(P0-3 硬规则):后台已有出货的机器+SKU+日期,手工再录出库 → 碰撞拒绝")
    void manualTransferCollisionBlocked() {
        Object[] ctx = setup();
        Machine m = (Machine) ctx[0];
        Product p = (Product) ctx[1];

        // 兑换活动当天后台已出货(导入转移单在),小邱又想手工录一张出库 → 拦
        Long manualId = docService.createDoc(req(DocType.TRANSFER_OUT, m.getId(),
                DocService.SOURCE_MANUAL, DAY, new Object[]{p.getId(), "2", "2.0"}), OP);
        docService.submit(manualId, OP);
        BizException e = assertThrows(BizException.class,
                () -> docService.confirm(manualId, OP, false, null));
        assertTrue(e.getMessage().contains("禁止手工再录出库"), "碰撞检查人话报错:" + e.getMessage());

        // 库存没被双扣
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("2")),
                "仓库仍是 10−8=2");
    }

    @Test
    @DisplayName("兑换成本对冲载体在位:deduction 表(ded_source=兑换,supplier_id 必填)")
    void deductionCarrierSchemaReady() {
        assertTableExists("yc_vend_deduction");
        Map<String, Object> source = assertColumn("yc_vend_deduction", "ded_source");
        assertEquals("兑换", String.valueOf(source.get("COLUMN_DEFAULT")), "抵扣来源默认=兑换");
        assertColumn("yc_vend_deduction", "used_settle_bill_id");
    }

    @Test
    @Disabled("M3-pending:平台结算单服务未实现——缺\"settlement.system_amount 聚合仅 order_type=正常/退款(兑换不入待结算虚账)\"的业务断言;销售额口径已在本类第一例经成本引擎验过")
    void settlementSystemAmountExcludesExchange() {
        // M3 实装后:预生成平台结算单 → system_amount 只含正常+退款负
    }

    @Test
    @Disabled("M3-pending:兑换成本由厂家补贴到账对冲(deduction 待抵扣→结算单抵扣)依赖钱账链,M3 实装;载体表已验")
    void exchangeCostOffsetBySubsidy() {
        // M3 实装后:兑换确认单形成待抵扣 → 下张应付结算单抵扣 → 毛利口径闭环
    }
}
