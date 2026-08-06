package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.report.service.CostEngine;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.DeductionMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;
import top.aole.vend.modules.settle.service.DeductionService;
import top.aole.vend.modules.settle.service.SettleBillService;
import top.aole.vend.modules.settlement.domain.entity.Settlement;
import top.aole.vend.modules.settlement.dto.SettlementDtos;
import top.aole.vend.modules.settlement.mapper.SettlementMapper;
import top.aole.vend.modules.settlement.service.SettlementService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景4:兑换出库(审计结论:错——订单类型未分流 → 污染待结算虚账+销售额,且可能双扣库存;
 * P0-3 三口径修复:①结算口径=仅正常(退款负) ②补货口径=正常+兑换 ③毛利:兑换收入0计成本)。
 */
class Scenario04ExchangeOrderTest extends RegressionSupport {

    @Autowired
    private CostEngine costEngine;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private SettlementMapper settlementMapper;
    @Autowired
    private SettleModeService settleModeService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private DeductionService deductionService;
    @Autowired
    private DeductionMapper deductionMapper;
    @Autowired
    private SettleBillMapper settleBillMapper;
    @Autowired
    private SettleBillService settleBillService;

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
    @DisplayName("三口径之结算口径(M3-3 转绿):平台结算单 system_amount 只聚合 正常+退款负,兑换/测试不入待结算虚账,回填也只碰正常/退款")
    void settlementSystemAmountExcludesExchange() {
        Object[] ctx = setup();
        Machine m = (Machine) ctx[0];
        Product p = (Product) ctx[1];

        SaleRecord normal = sale(m.getId(), p.getId(), "1", "5.0", "正常", DAY.atTime(10, 0));
        SaleRecord exchange = sale(m.getId(), p.getId(), "2", "7.0", "兑换", DAY.atTime(11, 0));  // 哪怕导出带金额7
        SaleRecord refund = sale(m.getId(), p.getId(), "1", "-3.0", "退款", DAY.atTime(12, 0));
        sale(m.getId(), p.getId(), "1", "99.0", "测试", DAY.atTime(13, 0));

        // 定型 PLATFORM(附录D)→ 录入平台结算单(区间=兑换活动当天)→ 平台账单凭证 → 确认
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
        MoneyDtos.AccountCreateReq accReq = new MoneyDtos.AccountCreateReq();
        accReq.setAccountName("微信-场景4-" + SEQ.incrementAndGet());
        accReq.setAccountType("微信");
        Long acc = accountService.create(accReq, OP, OPERATOR);

        SettlementDtos.BillCreateReq req = new SettlementDtos.BillCreateReq();
        req.setPeriodStart(DAY);
        req.setPeriodEnd(DAY);
        req.setPlatformAmount(new BigDecimal("2.0"));
        req.setFeeAmount(new BigDecimal("0.2"));
        req.setActualAmount(new BigDecimal("1.8"));
        req.setAccountId(acc);
        Long billId = settlementService.create(req, OP, OPERATOR);
        attachmentService.upload("settlement", billId, "平台账单", "场景4账单.png",
                new byte[]{1, 2, 3}, OP, OPERATOR);
        SettlementDtos.ConfirmResult r = settlementService.confirm(billId, OP, OPERATOR);

        // 审计结论修复点:兑换的 7 与测试的 99 都不进 system_amount(不污染待结算虚账)
        assertEquals(0, r.getSystemAmount().compareTo(new BigDecimal("2.0")),
                "system_amount=正常5−退款3=2,兑换/测试不入待结算(P0-3)");
        assertEquals(Settlement.ST_SETTLED, r.getStlStatus(), "两差全绿(账单2/手续费0.2/到账1.8)");
        assertEquals(2, r.getBackfillCount().intValue(), "只回填正常+退款两笔");
        assertEquals(billId, saleRecordMapper.selectById(normal.getId()).getSettlementId());
        assertEquals(billId, saleRecordMapper.selectById(refund.getId()).getSettlementId());
        assertNull(saleRecordMapper.selectById(exchange.getId()).getSettlementId(),
                "兑换单永不被结算单回填(它的钱走厂家补贴对冲,不走平台)");
    }

    @Test
    @DisplayName("兑换成本对冲闭环(M3-3 转绿):兑换出货成本 → 抵扣确认单(待抵扣)→ 应付结算单抵扣 → ROI 查询毛利口径闭环")
    void exchangeCostOffsetBySubsidy() {
        Object[] ctx = setup();
        Machine m = (Machine) ctx[0];
        Product p = (Product) ctx[1];

        // 基线快照(delta 口径,免受库内历史数据影响)
        SettlementDtos.ExchangeRoiResp before = settlementService.exchangeRoi(null, null);

        // 兑换出货 2 件,成本 4(移动加权快照;收入按 0)
        SaleRecord exchange = sale(m.getId(), p.getId(), "2", "0", "兑换", DAY.atTime(11, 0));
        exchange.setCostAmount(new BigDecimal("4.0"));
        saleRecordMapper.updateById(exchange);

        // 厂家兑换补贴形成待抵扣(抵扣确认单,supplier_id 必填防串户)
        Supplier sup = new Supplier();
        sup.setSupplierCode("RGS04" + SEQ.incrementAndGet());
        sup.setSupplierName("兑换活动厂家");
        sup.setSettleMethod("现结");
        sup.setAccountDays(0);
        sup.setOpeningPayable(BigDecimal.ZERO);
        sup.setCoopStatus("合作中");
        supplierMapper.insert(sup);
        SettleDtos.DeductionCreateReq dedReq = new SettleDtos.DeductionCreateReq();
        dedReq.setSupplierId(sup.getId());
        dedReq.setDedSource("兑换");
        dedReq.setAmount(new BigDecimal("4.0"));
        dedReq.setPeriodDesc("6月兑换活动补贴,对冲出货成本");
        Long dedId = deductionService.create(dedReq, OP, OPERATOR);

        // 下张应付结算单带入抵扣(M3-2 链):采购 10×2=20 → 结算单 20−4=16
        top.aole.vend.modules.doc.dto.DocCreateReq poReq = req(DocType.PURCHASE_IN, null,
                DocService.SOURCE_MANUAL, DAY, new Object[]{p.getId(), "10", "2.0"});
        poReq.setSupplierId(sup.getId());
        Long docId = docService.createDoc(poReq, OP);
        docService.submit(docId, OP);
        docService.confirm(docId, OP, false, null);
        SettleBill bill = settleBillMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSourceDocId, docId).eq(SettleBill::getDirection, "正常"));
        assertNotNull(bill, "采购确认自动生成应付结算单");
        SettleBillService.ConfirmResult cr = settleBillService.confirm(
                bill.getId(), Collections.singletonList(dedId), OP, OPERATOR, "老板");
        assertEquals(0, cr.getAmountActual().compareTo(new BigDecimal("16.0")), "货款 20 − 兑换补贴 4 = 实结 16");
        assertEquals("已抵扣", deductionMapper.selectById(dedId).getDedStatus());
        assertEquals(bill.getId(), deductionMapper.selectById(dedId).getUsedSettleBillId());

        // ROI 查询(delta 口径,免受库内历史数据影响):成本 4 全部被补贴 4 盖住 → 毛利口径闭环
        SettlementDtos.ExchangeRoiResp after = settlementService.exchangeRoi(null, null);
        assertEquals(0, after.getExchangeCost().subtract(before.getExchangeCost())
                .compareTo(new BigDecimal("4.0")), "Σ兑换出货成本 +4");
        assertEquals(0, after.getSubsidyUsed().subtract(before.getSubsidyUsed())
                .compareTo(new BigDecimal("4.0")), "Σ厂家补贴已抵扣 +4(deduction 已确认并用于结算单)");
        assertEquals(0, after.getSubsidyConfirmed().subtract(before.getSubsidyConfirmed())
                .compareTo(new BigDecimal("4.0")));
        assertEquals(0, after.getNet().subtract(before.getNet()).compareTo(BigDecimal.ZERO),
                "净对冲增量=补贴4−成本4=0:兑换单成本由补贴到账完全对冲(§13.2-3 毛利口径闭环)");
    }
}
