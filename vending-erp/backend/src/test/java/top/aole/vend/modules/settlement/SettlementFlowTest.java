package top.aole.vend.modules.settlement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.service.DeductionService;
import top.aole.vend.modules.settlement.domain.entity.Settlement;
import top.aole.vend.modules.settlement.dto.SettlementDtos;
import top.aole.vend.modules.settlement.mapper.SettlementMapper;
import top.aole.vend.modules.settlement.service.SettlementService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3-3 平台结算双模式集成测试(vend_test_settlement 独立库,Flyway 自迁 V1.0.11,每例事务回滚)。
 *
 * 覆盖验收清单:①三模式行为各自正确(UNSET 横幅+假设预览/PLATFORM 全链/DIRECT 核对单)
 * ②待结算口径(兑换/测试/线下不计,退款为负) ③两差红灯+差异挂起复核 ④回填幂等+防双击
 * ⑤凭证门禁 ⑥模式快照防呆 ⑦入账账户校验 ⑧兑换 ROI(补贴对冲)。
 */
@ActiveProfiles("test-settlement")
class SettlementFlowTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String OPERATOR = "结算测试员";
    private static final LocalDate D1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate D31 = LocalDate.of(2026, 7, 31);

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
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private DeductionService deductionService;
    @Autowired
    private SupplierMapper supplierMapper;

    // ============================== 造数工具 ==============================

    private Long realAccount(String opening) {
        MoneyDtos.AccountCreateReq req = new MoneyDtos.AccountCreateReq();
        req.setAccountName("微信-结算" + SEQ.incrementAndGet());
        req.setAccountType("微信");
        req.setOpeningBalance(new BigDecimal(opening));
        return accountService.create(req, OP, OPERATOR);
    }

    private Long virtualAccount() {
        MoneyDtos.AccountCreateReq req = new MoneyDtos.AccountCreateReq();
        req.setAccountName("平台待结算" + SEQ.incrementAndGet());
        req.setAccountType("平台待结算");
        return accountService.create(req, OP, OPERATOR);
    }

    /** 直落 sale_record(导入通道替身):金额/类型/成本/线下标记 */
    private SaleRecord saleFull(String amount, String orderType, String cost, boolean offline,
                                LocalDateTime bizTime) {
        SaleRecord s = new SaleRecord();
        s.setOrderNo("ST-" + SEQ.incrementAndGet());
        s.setQty(BigDecimal.ONE);
        s.setAmountReceived(new BigDecimal(amount));
        s.setOrderType(orderType);
        s.setCostAmount(cost == null ? null : new BigDecimal(cost));
        s.setOfflineFlag(offline);
        s.setBizTime(bizTime);
        s.setBizPeriod(bizTime.format(PERIOD));
        s.setBookPeriod(s.getBizPeriod());
        saleRecordMapper.insert(s);
        return s;
    }

    private SettlementDtos.BillCreateReq platformReq(String platform, String fee, String actual, Long accountId) {
        SettlementDtos.BillCreateReq req = new SettlementDtos.BillCreateReq();
        req.setPeriodStart(D1);
        req.setPeriodEnd(D31);
        req.setPlatformAmount(new BigDecimal(platform));
        if (fee != null) {
            req.setFeeAmount(new BigDecimal(fee));
        }
        if (actual != null) {
            req.setActualAmount(new BigDecimal(actual));
        }
        req.setAccountId(accountId);
        return req;
    }

    private void voucher(Long billId) {
        attachmentService.upload(SettlementService.ATTACH_REF_TYPE, billId, "平台账单",
                "平台账单截图.png", new byte[]{1, 2, 3}, OP, OPERATOR);
    }

    private List<CashFlow> flowsOf(Long billId) {
        return cashFlowMapper.selectList(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, SettlementService.REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, billId));
    }

    // ============================== 用例 ==============================

    @Test
    @DisplayName("① UNSET:总览带横幅+两模式假设对比(标非正式);录入/确认都被拦(指路设置中心)")
    void unsetBannerAndHypothesisPreview() {
        assertEquals(SettleModeService.MODE_UNSET, settleModeService.currentMode(), "默认待核实");
        saleFull("5.0", "正常", "2.0", false, D1.atTime(10, 0));
        saleFull("-3.0", "退款", "-1.0", false, D1.atTime(11, 0));

        SettlementDtos.OverviewResp ov = settlementService.overview();
        assertEquals(SettleModeService.UNSET_BANNER, ov.getBanner(), "横幅原文返回");
        assertNull(ov.getPendingBalance(), "UNSET 不出正式数");
        assertEquals(2, ov.getHypothesis().size(), "两模式各一版参考数");
        for (SettlementDtos.HypothesisView h : ov.getHypothesis()) {
            assertEquals(Boolean.TRUE, h.getInformal(), "假设预览必须标非正式");
            assertEquals(0, h.getAmount().compareTo(new BigDecimal("2.0")), "参考数=Σ正常−退款=2");
        }

        BizException e = assertThrows(BizException.class,
                () -> settlementService.create(platformReq("2", "0", "2", null), OP, OPERATOR));
        assertTrue(e.getMessage().contains("待核实"), "录入被拦并指路:" + e.getMessage());
    }

    @Test
    @DisplayName("② PLATFORM 待结算口径(P0-3):仅正常−退款;兑换/测试/线下补录一律不计")
    void platformPendingBalanceScope() {
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
        saleFull("5.0", "正常", "2.0", false, D1.atTime(10, 0));
        saleFull("-3.0", "退款", "-1.0", false, D1.atTime(11, 0));
        saleFull("7.0", "兑换", "4.0", false, D1.atTime(12, 0));    // 不入待结算
        saleFull("99.0", "测试", null, false, D1.atTime(13, 0));    // 不计
        saleFull("10.0", "线下补录", "3.0", true, D1.atTime(14, 0)); // 钱已直收,不在平台

        SettlementDtos.OverviewResp ov = settlementService.overview();
        assertNull(ov.getBanner(), "定型后横幅消失");
        assertEquals(0, ov.getPendingBalance().compareTo(new BigDecimal("2.0")), "待结算=5−3=2");
        assertEquals(2L, ov.getPendingCount().longValue(), "只有正常+退款两笔在途");
        assertNotNull(ov.getPendingOldest());
    }

    @Test
    @DisplayName("③ PLATFORM 全链:凭证→确认=快照+两差绿灯+回填+两笔流水(货款结算毛额收/手续费支),净入账=实际到账")
    void platformFullChain() {
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
        Long acc = realAccount("0");
        SaleRecord s1 = saleFull("600.0", "正常", "200.0", false, D1.atTime(9, 0));
        SaleRecord s2 = saleFull("-100.0", "退款", "-30.0", false, D31.atTime(20, 0));
        saleFull("50.0", "正常", "15.0", false, LocalDate.of(2026, 8, 2).atTime(9, 0)); // 区间外,不动

        Long billId = settlementService.create(platformReq("500", "30", "470", acc), OP, OPERATOR);
        assertTrue(settlementMapper.selectById(billId).getStmtNo().startsWith("PT-"), "平台结算单 PT- 前缀");
        voucher(billId);
        SettlementDtos.ConfirmResult r = settlementService.confirm(billId, OP, OPERATOR);

        assertEquals(0, r.getSystemAmount().compareTo(new BigDecimal("500")), "系统额快照=600−100(仅区间内待回填)");
        assertEquals(0, r.getDiffSales().compareTo(BigDecimal.ZERO), "漏单差=0");
        assertEquals(0, r.getDiffArrival().compareTo(BigDecimal.ZERO), "扣款差=(500−30)−470=0");
        assertTrue(r.getSalesDiffOk() && r.getArrivalDiffOk(), "两灯全绿");
        assertEquals(Settlement.ST_SETTLED, r.getStlStatus());
        assertEquals(2, r.getBackfillCount().intValue(), "回填区间内 2 笔");
        assertEquals(2, r.getFlowCount().intValue());

        // 回填验证:正常/退款回填,区间外不动;待结算余额只剩区间外那笔
        assertEquals(billId, saleRecordMapper.selectById(s1.getId()).getSettlementId());
        assertEquals(billId, saleRecordMapper.selectById(s2.getId()).getSettlementId());
        assertEquals(0, settlementService.overview().getPendingBalance().compareTo(new BigDecimal("50")));

        // 两笔流水:货款结算 收 500(毛额=470+30,不进利润表)+ 平台手续费 支 30(利润表行)
        List<CashFlow> flows = flowsOf(billId);
        assertEquals(2, flows.size());
        CashFlow in = flows.stream().filter(f -> "收".equals(f.getDirection())).findFirst().orElseThrow(AssertionError::new);
        CashFlow out = flows.stream().filter(f -> "支".equals(f.getDirection())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("货款结算", in.getCategory());
        assertEquals("不进利润表", in.getPlLine(), "虚账划转不再进利润表(收入已在销售报表确认,防双记)");
        assertEquals(0, in.getAmount().compareTo(new BigDecimal("500")));
        assertEquals("平台手续费", out.getCategory());
        assertEquals("平台手续费", out.getPlLine(), "报表单列「平台吃掉多少钱」");
        assertEquals(0, out.getAmount().compareTo(new BigDecimal("30")));
        assertEquals(0, accountService.balanceOf(acc).compareTo(new BigDecimal("470")), "净入账=实际到账");
        assertEquals("2026-07", settlementMapper.selectById(billId).getBookPeriod());
    }

    @Test
    @DisplayName("④ 凭证硬门禁:无平台账单凭证确认 → 拦且 0 流水 0 回填;传凭证后放行")
    void voucherHardGate() {
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
        Long acc = realAccount("0");
        SaleRecord s1 = saleFull("100.0", "正常", "40.0", false, D1.atTime(9, 0));
        Long billId = settlementService.create(platformReq("100", "5", "95", acc), OP, OPERATOR);

        BizException e = assertThrows(BizException.class,
                () -> settlementService.confirm(billId, OP, OPERATOR));
        assertTrue(e.getMessage().contains("凭证"), "人话报错:" + e.getMessage());
        assertEquals(0, flowsOf(billId).size(), "被拦时 0 流水");
        assertNull(saleRecordMapper.selectById(s1.getId()).getSettlementId(), "被拦时 0 回填");

        voucher(billId);
        SettlementDtos.ConfirmResult r = settlementService.confirm(billId, OP, OPERATOR);
        assertEquals(Settlement.ST_SETTLED, r.getStlStatus(), "传凭证后放行");
    }

    @Test
    @DisplayName("⑤ 两差红灯:漏单差/扣款差超阈值±1 → 差异挂起(钱与回填照落);复核说明必填后收口")
    void diffRedLightAndResolve() {
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
        Long acc = realAccount("0");
        saleFull("100.0", "正常", "40.0", false, D1.atTime(9, 0));
        // 平台账单 120(漏单差=100−120=−20 红);预计 120−10=110 vs 到账 105(扣款差=5 红)
        Long billId = settlementService.create(platformReq("120", "10", "105", acc), OP, OPERATOR);
        voucher(billId);
        SettlementDtos.ConfirmResult r = settlementService.confirm(billId, OP, OPERATOR);

        assertEquals(Settlement.ST_DIFF, r.getStlStatus(), "任一差超阈值=差异挂起");
        assertFalse(r.getSalesDiffOk());
        assertFalse(r.getArrivalDiffOk());
        assertEquals(0, r.getDiffSales().compareTo(new BigDecimal("-20")), "漏单差=系统−账单");
        assertEquals(0, r.getDiffArrival().compareTo(new BigDecimal("5")), "扣款差=预计−实际");
        assertEquals(0, accountService.balanceOf(acc).compareTo(new BigDecimal("105")), "钱到账是事实,照落=净105");
        assertEquals(1, r.getBackfillCount().intValue(), "回填照做(差异只是报警,不悬置货款)");

        assertThrows(BizException.class, () -> settlementService.resolveDiff(billId, "  ", OP, OPERATOR));
        settlementService.resolveDiff(billId, "平台活动补贴扣款 5 元,账单口径含测试机 20 元", OP, OPERATOR);
        Settlement after = settlementMapper.selectById(billId);
        assertEquals(Settlement.ST_SETTLED, after.getStlStatus(), "复核收口");
        assertTrue(after.getDiffNote().contains("扣款"), "结论留痕");
    }

    @Test
    @DisplayName("⑥ 回填幂等+防双击:二次确认被拒;已归属行永不重写;下一单只吃新增在途")
    void backfillIdempotentAndDoubleClickGuard() {
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
        Long acc = realAccount("0");
        SaleRecord s1 = saleFull("100.0", "正常", "40.0", false, D1.atTime(9, 0));
        Long bill1 = settlementService.create(platformReq("100", "0", "100", acc), OP, OPERATOR);
        voucher(bill1);
        settlementService.confirm(bill1, OP, OPERATOR);

        BizException e = assertThrows(BizException.class,
                () -> settlementService.confirm(bill1, OP, OPERATOR));
        assertTrue(e.getMessage().contains("防双击") || e.getMessage().contains("待核对"), e.getMessage());
        assertEquals(1, flowsOf(bill1).size(), "fee=0 只落货款结算 1 条;双击没有第二组流水");

        // 补导来了一笔同区间新销售 → 仍在途;第二张结算单只吃它,老行归属不被改写
        SaleRecord s2 = saleFull("30.0", "正常", "10.0", false, D31.atTime(21, 0));
        Long bill2 = settlementService.create(platformReq("30", "0", "30", acc), OP, OPERATOR);
        voucher(bill2);
        SettlementDtos.ConfirmResult r2 = settlementService.confirm(bill2, OP, OPERATOR);
        assertEquals(0, r2.getSystemAmount().compareTo(new BigDecimal("30")), "快照只含新增在途");
        assertEquals(1, r2.getBackfillCount().intValue());
        assertEquals(bill1, saleRecordMapper.selectById(s1.getId()).getSettlementId(), "已归属行不被 bill2 重写");
        assertEquals(bill2, saleRecordMapper.selectById(s2.getId()).getSettlementId());
    }

    @Test
    @DisplayName("⑦ DIRECT 核对单:HD-前缀,确认只对差——0 流水 0 回填(钱已直连入账,防双记);超差也只挂起提示")
    void directCheckBillNoMoneyNoBackfill() {
        settleModeService.set(SettleModeService.MODE_DIRECT, OP, OPERATOR);
        SaleRecord s1 = saleFull("5.0", "正常", "2.0", false, D1.atTime(10, 0));
        saleFull("-3.0", "退款", "-1.0", false, D1.atTime(11, 0));
        saleFull("7.0", "兑换", "4.0", false, D1.atTime(12, 0));

        SettlementDtos.OverviewResp ov = settlementService.overview();
        assertNull(ov.getPendingBalance(), "直连无待结算虚账");
        assertNotNull(ov.getDirectNote());

        // 对平:账单 2 = 系统 5−3
        SettlementDtos.BillCreateReq req = platformReq("2", null, null, null);
        Long ok = settlementService.create(req, OP, OPERATOR);
        assertTrue(settlementMapper.selectById(ok).getStmtNo().startsWith("HD-"), "核对单 HD- 前缀");
        SettlementDtos.ConfirmResult r = settlementService.confirm(ok, OP, OPERATOR);
        assertEquals(Settlement.ST_CHECKED, r.getStlStatus());
        assertEquals(0, r.getSystemAmount().compareTo(new BigDecimal("2")), "口径同三口径:兑换不计");
        assertEquals(0, r.getFlowCount().intValue(), "不落收入流水");
        assertEquals(0, r.getBackfillCount().intValue(), "不回填");
        assertNull(saleRecordMapper.selectById(s1.getId()).getSettlementId(), "settlement_id 不动");

        // 对不平:只出差异报告,同样不动钱
        Long bad = settlementService.create(platformReq("50", null, null, null), OP, OPERATOR);
        SettlementDtos.ConfirmResult r2 = settlementService.confirm(bad, OP, OPERATOR);
        assertEquals(Settlement.ST_DIFF, r2.getStlStatus(), "超差挂起(提示核实,不动钱)");
        assertEquals(0, flowsOf(bad).size());
    }

    @Test
    @DisplayName("⑧ 模式快照防呆:PLATFORM 录的单,定型改 DIRECT 后确认被拒 → 作废重录;二次作废被拒")
    void modeSnapGuard() {
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
        Long acc = realAccount("0");
        Long billId = settlementService.create(platformReq("10", "1", "9", acc), OP, OPERATOR);

        settleModeService.set(SettleModeService.MODE_DIRECT, OP, OPERATOR);
        BizException e = assertThrows(BizException.class,
                () -> settlementService.confirm(billId, OP, OPERATOR));
        assertTrue(e.getMessage().contains("重录"), "指路作废重录:" + e.getMessage());

        settlementService.voidBill(billId, OP, OPERATOR);
        assertEquals(Settlement.ST_VOID, settlementMapper.selectById(billId).getStlStatus());
        assertThrows(BizException.class, () -> settlementService.voidBill(billId, OP, OPERATOR));

        // 按当前模式(DIRECT)重录即可
        Long redo = settlementService.create(platformReq("10", null, null, null), OP, OPERATOR);
        assertTrue(settlementMapper.selectById(redo).getStmtNo().startsWith("HD-"));
    }

    @Test
    @DisplayName("⑨ PLATFORM 录入校验:虚拟账户拒/停用账户拒/手续费缺失拒/区间倒置拒")
    void platformCreateValidation() {
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
        Long virtual = virtualAccount();
        BizException e1 = assertThrows(BizException.class,
                () -> settlementService.create(platformReq("10", "1", "9", virtual), OP, OPERATOR));
        assertTrue(e1.getMessage().contains("虚拟账户"), e1.getMessage());

        Long stopped = realAccount("0");
        accountService.changeStatus(stopped, 0, OP, OPERATOR);
        assertThrows(BizException.class,
                () -> settlementService.create(platformReq("10", "1", "9", stopped), OP, OPERATOR));

        Long acc = realAccount("0");
        assertThrows(BizException.class,
                () -> settlementService.create(platformReq("10", null, "9", acc), OP, OPERATOR));

        SettlementDtos.BillCreateReq inverted = platformReq("10", "1", "9", acc);
        inverted.setPeriodStart(D31);
        inverted.setPeriodEnd(D1);
        assertThrows(BizException.class, () -> settlementService.create(inverted, OP, OPERATOR));
    }

    @Test
    @DisplayName("⑩ 兑换活动 ROI:Σ兑换出货成本 vs Σ补贴确认额(非作废);成本缺数行单独报,不按 0 硬算")
    void exchangeRoiOffset() {
        saleFull("0", "兑换", "4.0", false, D1.atTime(10, 0));
        saleFull("0", "兑换", "2.5", false, D1.atTime(11, 0));
        saleFull("0", "兑换", null, false, D1.atTime(12, 0));   // 无采购史:缺数另报
        saleFull("5.0", "正常", "2.0", false, D1.atTime(13, 0)); // 正常单不进兑换成本

        Supplier sup = new Supplier();
        sup.setSupplierCode("STS" + SEQ.incrementAndGet());
        sup.setSupplierName("兑换厂家");
        sup.setSettleMethod("现结");
        sup.setAccountDays(0);
        sup.setOpeningPayable(BigDecimal.ZERO);
        sup.setCoopStatus("合作中");
        supplierMapper.insert(sup);

        SettleDtos.DeductionCreateReq d1 = new SettleDtos.DeductionCreateReq();
        d1.setSupplierId(sup.getId());
        d1.setDedSource("兑换");
        d1.setAmount(new BigDecimal("5.00"));
        deductionService.create(d1, OP, OPERATOR);
        SettleDtos.DeductionCreateReq d2 = new SettleDtos.DeductionCreateReq();
        d2.setSupplierId(sup.getId());
        d2.setDedSource("厂家补贴");
        d2.setAmount(new BigDecimal("3.00"));
        Long voidId = deductionService.create(d2, OP, OPERATOR);
        deductionService.voidDeduction(voidId, OP, OPERATOR);   // 作废不计

        SettlementDtos.ExchangeRoiResp roi = settlementService.exchangeRoi(D1, D31);
        assertEquals(0, roi.getExchangeCost().compareTo(new BigDecimal("6.5")), "兑换成本=4+2.5(NULL 不计)");
        assertEquals(1L, roi.getCostMissingCount().longValue(), "缺数行单独报(§13.2-6 禁按 0 硬算)");
        assertEquals(0, roi.getSubsidyPending().compareTo(new BigDecimal("5.00")), "待抵扣 5");
        assertEquals(0, roi.getSubsidyUsed().compareTo(BigDecimal.ZERO));
        assertEquals(0, roi.getSubsidyConfirmed().compareTo(new BigDecimal("5.00")), "作废的 3 不计");
        assertEquals(0, roi.getNet().compareTo(new BigDecimal("-1.5")), "净对冲=5−6.5(还差 1.5 没被补贴盖住)");
    }
}
