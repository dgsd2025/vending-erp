package top.aole.vend.modules.settle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.doc.service.RedFlushService;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.settle.domain.entity.Deduction;
import top.aole.vend.modules.settle.domain.entity.Payment;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.DeductionMapper;
import top.aole.vend.modules.settle.mapper.PaymentMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;
import top.aole.vend.modules.settle.service.DeductionService;
import top.aole.vend.modules.settle.service.PayableService;
import top.aole.vend.modules.settle.service.PaymentService;
import top.aole.vend.modules.settle.service.SettleBillService;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3-2 应付供应商全链集成测试(vend_test_settle 独立库,Flyway 自迁 V1.0.8,每例事务回滚)。
 *
 * 契约来源:§9.2 采购付款全链路 + §7.3 问2 应付余额口径 + §13.2-1 红冲应付红字 + M3-1 基座
 * (MoneyPostingEvent / 凭证 countOf 硬门禁 / balanceOf)。
 *
 * §9.2 样板数字说明:调研报告"2254 − 351.63 = 1911.37"两数自相矛盾(2254−351.63=1902.37;
 * 表注又写 2254−342.63=1911.37)。本测试取真实抵扣 351.63,系统按公式算出 1902.37——
 * 规则引擎出数字,不迁就报告里的手误。
 */
@ActiveProfiles("test-settle")
class SettleFlowTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final String OPERATOR = "验收员";
    private static final String ROLE_BOSS = "老板";

    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private MachineMapper machineMapper;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private SettleBillMapper billMapper;
    @Autowired
    private PaymentMapper paymentMapper;
    @Autowired
    private DeductionMapper deductionMapper;
    @Autowired
    private SettleBillService settleBillService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private DeductionService deductionService;
    @Autowired
    private PayableService payableService;
    @Autowired
    private RedFlushService redFlushService;

    // ============================== 造数工具 ==============================

    private Supplier supplier(String name, String settleMethod, int accountDays, String openingPayable) {
        Supplier s = new Supplier();
        s.setSupplierCode("ST" + SEQ.incrementAndGet());
        s.setSupplierName(name);
        s.setSettleMethod(settleMethod);
        s.setAccountDays(accountDays);
        s.setOpeningPayable(new BigDecimal(openingPayable));
        s.setCoopStatus("合作中");
        supplierMapper.insert(s);
        return s;
    }

    private Product product(String name) {
        Product p = new Product();
        p.setSkuCode("STP" + SEQ.incrementAndGet());
        p.setProductName(name);
        p.setUnit("瓶");
        p.setProductStatus("在售");
        productMapper.insert(p);
        return p;
    }

    private Account account(String name, String opening) {
        Account a = new Account();
        a.setAccountName("ST微信-" + name + SEQ.incrementAndGet());
        a.setAccountType("微信");
        a.setIsVirtual(false);
        a.setOpeningBalance(new BigDecimal(opening));
        a.setOpeningSetAt(LocalDateTime.now());
        accountMapper.insert(a);
        return a;
    }

    /** 采购入库(带供应商)建单→提交→确认:触发结算单自动生成 */
    private Long purchase(Long supplierId, Long productId, String qty, String price, LocalDate bizDate) {
        top.aole.vend.modules.doc.dto.DocCreateReq r = req(DocType.PURCHASE_IN, null,
                DocService.SOURCE_MANUAL, bizDate, new Object[]{productId, qty, price});
        r.setSupplierId(supplierId);
        Long id = docService.createDoc(r, OP);
        docService.submit(id, OP);
        docService.confirm(id, OP, false, null);
        return id;
    }

    private SettleBill billOf(Long docId) {
        return billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSourceDocId, docId)
                .eq(SettleBill::getDirection, SettleBill.DIR_NORMAL).last("LIMIT 1"));
    }

    private Long deduction(Long supplierId, String source, String amount, String desc) {
        SettleDtos.DeductionCreateReq req = new SettleDtos.DeductionCreateReq();
        req.setSupplierId(supplierId);
        req.setDedSource(source);
        req.setAmount(new BigDecimal(amount));
        req.setPeriodDesc(desc);
        return deductionService.create(req, OP, OPERATOR);
    }

    private Long payment(Long supplierId, Long accountId, String amount, Long billId) {
        SettleDtos.PaymentCreateReq req = new SettleDtos.PaymentCreateReq();
        req.setSupplierId(supplierId);
        req.setAccountId(accountId);
        req.setAmount(new BigDecimal(amount));
        req.setSettleBillId(billId);
        return paymentService.create(req, OP, OPERATOR);
    }

    private void voucher(Long payId) {
        attachmentService.upload("payment", payId, "转账截图", "微信转账.png",
                "png-bytes".getBytes(StandardCharsets.UTF_8), OP, OPERATOR);
    }

    private SettleDtos.SupplierOverviewRow overview(Long supplierId) {
        return payableService.overview().stream()
                .filter(r -> r.getSupplierId().equals(supplierId)).findFirst().orElseThrow(AssertionError::new);
    }

    // ============================== 用例 ==============================

    @Test
    @DisplayName("1·结算单自动生成:采购入库确认→待确认结算单(应结=实收金额,月结带到期日);无供应商采购不产生应付")
    void autoGenerateOnPurchaseConfirm() {
        Supplier s = supplier("陈老板", "月结", 15, "0");
        Product p = product("东鹏特饮");
        LocalDate bizDate = LocalDate.now().minusDays(1);
        Long docId = purchase(s.getId(), p.getId(), "100", "3.5", bizDate);

        SettleBill bill = billOf(docId);
        assertNotNull(bill, "采购入库确认自动生成结算单(唯一生产者=确认事件)");
        assertEquals(SettleBill.ST_PENDING_CONFIRM, bill.getBillStatus());
        assertEquals(0, bill.getAmountDue().compareTo(new BigDecimal("350.00")), "应结=实收入库金额");
        assertEquals(0, bill.getAmountActual().compareTo(new BigDecimal("350.00")), "未带抵扣前实结=应结");
        assertEquals(bizDate.plusDays(15), bill.getDueDate(), "月结15天:到期日=入库日+账期");
        assertEquals(DocStatus.CONFIRMED, docHeadMapper.selectById(docId).getDocStatus(),
                "采购单停在已确认,老板复核结算单时才进待结算");

        // 无供应商的采购垫底单:不产生应付
        Product p2 = product("无主商品");
        Long noSupplierDoc = stockWarehouse(p2.getId(), "10");
        assertNull(billOf(noSupplierDoc), "无供应商不生成结算单");
    }

    @Test
    @DisplayName("2·§9.2 样板复刻:2254 拿货 → 带入待抵扣351.63 → 实结1902.37;抵扣→已抵扣锚定结算单;采购单→待结算")
    void sampleFlowSection92() {
        Supplier s = supplier("陈老板92", "现结", 0, "0");
        Product p = product("7-29批货");
        Long docId = purchase(s.getId(), p.getId(), "644", "3.5", LocalDate.now()); // 644×3.5=2254
        Long dedId = deduction(s.getId(), "兑换", "351.63", "厂家已结账(7月兑换)");

        SettleBill bill = billOf(docId);
        SettleBillService.ConfirmResult result = settleBillService.confirm(
                bill.getId(), Collections.singletonList(dedId), OP, OPERATOR, ROLE_BOSS);

        assertEquals(0, result.getAmountDue().compareTo(new BigDecimal("2254.00")));
        assertEquals(0, result.getDeductionAmount().compareTo(new BigDecimal("351.63")), "抵扣自动带入");
        assertEquals(0, result.getAmountActual().compareTo(new BigDecimal("1902.37")), "实结=应结−抵扣(系统算数)");

        SettleBill after = billMapper.selectById(bill.getId());
        assertEquals(SettleBill.ST_PENDING_PAY, after.getBillStatus(), "老板复核→待付款");
        Deduction ded = deductionMapper.selectById(dedId);
        assertEquals(Deduction.ST_USED, ded.getDedStatus(), "待抵扣→已抵扣");
        assertEquals(bill.getId(), ded.getUsedSettleBillId(), "锚定被使用的结算单(UI 显示已用于结算单X)");
        assertEquals(DocStatus.PENDING_SETTLE, docHeadMapper.selectById(docId).getDocStatus(),
                "采购单 已确认→待结算(应付环节开始)");
    }

    @Test
    @DisplayName("3·老板角色头门禁:非老板复核结算单被拒(与解锁同一把尺子);抵扣超应结也被拒")
    void bossRoleGateAndOverDeduction() {
        Supplier s = supplier("角色门禁供应商", "现结", 0, "0");
        Product p = product("门禁商品");
        Long docId = purchase(s.getId(), p.getId(), "10", "3.5", LocalDate.now());
        SettleBill bill = billOf(docId);

        BizException e = assertThrows(BizException.class,
                () -> settleBillService.confirm(bill.getId(), null, OP, OPERATOR, "录单员"));
        assertTrue(e.getMessage().contains("限老板角色"), "确认点②必须老板(§9.2 与①不同人)");

        Long bigDed = deduction(s.getId(), "厂家补贴", "999", "超额补贴");
        BizException e2 = assertThrows(BizException.class,
                () -> settleBillService.confirm(bill.getId(), Collections.singletonList(bigDed), OP, OPERATOR, ROLE_BOSS));
        assertTrue(e2.getMessage().contains("超过应结金额"), "抵扣35>应结不许透支,留给下一单");
    }

    @Test
    @DisplayName("4·防串户(修穿行场景13):新供应商结算单带入老供应商待抵扣→拒绝;同供应商放行")
    void crossSupplierDeductionRejected() {
        Supplier oldS = supplier("蔡彩云", "现结", 0, "0");
        Supplier newS = supplier("陈老板13", "现结", 0, "0");
        Long oldDed = deduction(oldS.getId(), "兑换", "351.63", "老供应商最后一期兑换");
        Product p = product("切换期商品");
        Long docId = purchase(newS.getId(), p.getId(), "200", "3.5", LocalDate.now());
        SettleBill bill = billOf(docId);

        BizException e = assertThrows(BizException.class, () -> settleBillService.confirm(
                bill.getId(), Collections.singletonList(oldDed), OP, OPERATOR, ROLE_BOSS));
        assertTrue(e.getMessage().contains("同供应商"), "只允许带入同供应商待抵扣(P2-11)");
        assertEquals(Deduction.ST_PENDING, deductionMapper.selectById(oldDed).getDedStatus(), "被拒后原样待抵扣");

        Long sameDed = deduction(newS.getId(), "兑换", "50", "新供应商首期");
        SettleBillService.ConfirmResult ok = settleBillService.confirm(
                bill.getId(), Collections.singletonList(sameDed), OP, OPERATOR, ROLE_BOSS);
        assertEquals(0, ok.getAmountActual().compareTo(new BigDecimal("650.00")), "700−50 同供应商正常带入");
    }

    @Test
    @DisplayName("5·凭证硬门禁:无转账截图确认付款被拒(countOf 断言);传凭证后放行;虚拟账户不许付货款")
    void voucherHardGate() {
        Supplier s = supplier("凭证供应商", "现结", 0, "0");
        Account a = account("凭证", "5000");
        Long payId = payment(s.getId(), a.getId(), "100", null);

        BizException e = assertThrows(BizException.class, () -> paymentService.confirm(payId, OP, OPERATOR));
        assertTrue(e.getMessage().contains("凭证"), "无凭证不能进已付款(§9.1-4)");
        assertEquals(Payment.ST_PENDING, paymentMapper.selectById(payId).getPayStatus(), "仍待付款");
        assertEquals(0, cashFlowMapper.selectCount(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, "付款单").eq(CashFlow::getRefDocId, payId)), "没落一分钱流水");

        voucher(payId);
        assertEquals(1, attachmentService.countOf("payment", payId), "凭证已挂(硬门禁取数口)");
        paymentService.confirm(payId, OP, OPERATOR);
        assertEquals(Payment.ST_SETTLED, paymentMapper.selectById(payId).getPayStatus());

        // 虚拟账户拒付
        Account virtual = new Account();
        virtual.setAccountName("ST平台待结算" + SEQ.incrementAndGet());
        virtual.setAccountType("平台待结算");
        virtual.setIsVirtual(true);
        accountMapper.insert(virtual);
        SettleDtos.PaymentCreateReq bad = new SettleDtos.PaymentCreateReq();
        bad.setSupplierId(s.getId());
        bad.setAccountId(virtual.getId());
        bad.setAmount(BigDecimal.TEN);
        BizException e2 = assertThrows(BizException.class, () -> paymentService.create(bad, OP, OPERATOR));
        assertTrue(e2.getMessage().contains("虚拟账户"));
    }

    @Test
    @DisplayName("6·付款核销闭环:金额=实结→流水(供应商付款/不进利润表)·结算单已完成·采购单已结算·账户余额减·应付归零")
    void paymentWriteOffClosedLoop() {
        Supplier s = supplier("闭环供应商", "现结", 0, "0");
        Product p = product("闭环商品");
        Account a = account("闭环", "5000");
        Long docId = purchase(s.getId(), p.getId(), "644", "3.5", LocalDate.now()); // 2254
        Long dedId = deduction(s.getId(), "兑换", "351.63", "厂家已结账");
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), Collections.singletonList(dedId), OP, OPERATOR, ROLE_BOSS);

        Long payId = payment(s.getId(), a.getId(), "1902.37", bill.getId());
        voucher(payId);
        Payment paid = paymentService.confirm(payId, OP, OPERATOR);

        assertEquals(Payment.ST_SETTLED, paid.getPayStatus(), "付款→结算完成");
        assertEquals(0, paid.getDeductionAmount().compareTo(new BigDecimal("351.63")), "付款单带抵扣额展示字段");
        assertEquals(SettleBill.ST_DONE, billMapper.selectById(bill.getId()).getBillStatus(), "结算单核销已完成");
        assertEquals(DocStatus.SETTLED, docHeadMapper.selectById(docId).getDocStatus(),
                "全链闭环:采购单→已结算(§9.2 结算完成)");

        List<CashFlow> flows = cashFlowMapper.selectList(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, "付款单").eq(CashFlow::getRefDocId, payId));
        assertEquals(1, flows.size(), "MoneyPostingEvent 落一条流水");
        assertEquals("供应商付款", flows.get(0).getCategory());
        assertEquals("不进利润表", flows.get(0).getPlLine(), "货款是本金往来(pl_line 由类别唯一推导)");
        assertEquals(CashFlow.DIR_OUT, flows.get(0).getDirection());
        assertEquals(0, accountService.balanceOf(a.getId()).compareTo(new BigDecimal("3097.63")),
                "账户余额 5000−1902.37");
        assertEquals(0, payableService.balance(s.getId()).compareTo(BigDecimal.ZERO),
                "应付余额=2254−351.63−1902.37=0(实时算)");
    }

    @Test
    @DisplayName("7·金额差异挂起(§9.2 差异处理):实付≠实结→付款/结算单双红灯;补说明后闭环、采购单已结算")
    void diffHangAndResolve() {
        Supplier s = supplier("差异供应商", "现结", 0, "0");
        Product p = product("差异商品");
        Account a = account("差异", "5000");
        Long docId = purchase(s.getId(), p.getId(), "100", "3.5", LocalDate.now()); // 350
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), null, OP, OPERATOR, ROLE_BOSS);

        Long payId = payment(s.getId(), a.getId(), "340", bill.getId()); // 少付10
        voucher(payId);
        Payment paid = paymentService.confirm(payId, OP, OPERATOR);

        assertEquals(Payment.ST_DIFF, paid.getPayStatus(), "凭证金额≠结算单金额→红灯挂起");
        SettleBill hung = billMapper.selectById(bill.getId());
        assertEquals(SettleBill.ST_DIFF, hung.getBillStatus());
        assertTrue(hung.getDiffNote().contains("-10"), "差异说明记差额:" + hung.getDiffNote());
        assertEquals(DocStatus.PENDING_SETTLE, docHeadMapper.selectById(docId).getDocStatus(), "挂起时采购单不闭环");
        assertEquals(0, payableService.balance(s.getId()).compareTo(new BigDecimal("10.00")),
                "钱已动:余额=350−340=10(挂起不影响实时余额)");

        // 补说明闭环
        paymentService.resolveDiff(payId, "供应商抹零10元", OP, OPERATOR);
        assertEquals(Payment.ST_SETTLED, paymentMapper.selectById(payId).getPayStatus());
        SettleBill closed = billMapper.selectById(bill.getId());
        assertEquals(SettleBill.ST_DONE, closed.getBillStatus());
        assertTrue(closed.getDiffNote().contains("抹零"), "补说明留痕");
        assertEquals(DocStatus.SETTLED, docHeadMapper.selectById(docId).getDocStatus(), "补说明后全链闭环");
    }

    @Test
    @DisplayName("8·应付余额公式各项+预付:期初+Σ采购−Σ退货−Σ抵扣−Σ付款;多付→负余额显示预付款")
    void balanceFormulaAndPrepay() {
        Supplier s = supplier("公式供应商", "现结", 0, "100"); // 期初 100
        Product p = product("公式商品");
        Machine m = new Machine();
        m.setMachineCode("STM" + SEQ.incrementAndGet());
        m.setMachineName("公式机");
        m.setDeviceId("STDEV" + SEQ.incrementAndGet());
        m.setMachineStatus("在线");
        machineMapper.insert(m);

        purchase(s.getId(), p.getId(), "100", "3.5", LocalDate.now()); // +350
        // 铺货到机器(导入来源),再退库退供 5×3.5(带供应商)= −17.5
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{p.getId(), "10", "3.5"});
        top.aole.vend.modules.doc.dto.DocCreateReq back = req(DocType.RETURN_BACK, m.getId(),
                DocService.SOURCE_MANUAL, LocalDate.now(), new Object[]{p.getId(), "5", "3.5"});
        back.setSupplierId(s.getId());
        Long backId = docService.createDoc(back, OP);
        docService.submit(backId, OP);
        docService.confirm(backId, OP, false, null);

        // 抵扣 30 用掉(带入结算单)
        Long dedId = deduction(s.getId(), "厂家补贴", "30", "8月补贴");
        SettleBill bill = billOf(docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getSupplierId, s.getId()).eq(DocHead::getDocType, DocType.PURCHASE_IN)).get(0).getId());
        settleBillService.confirm(bill.getId(), Collections.singletonList(dedId), OP, OPERATOR, ROLE_BOSS);
        // 未用的待抵扣不进公式
        deduction(s.getId(), "兑换", "999", "还没用的");

        SettleDtos.SupplierOverviewRow row = overview(s.getId());
        assertEquals(0, row.getOpeningPayable().compareTo(new BigDecimal("100.00")));
        assertEquals(0, row.getPurchaseTotal().compareTo(new BigDecimal("350.00")), "Σ已确认采购");
        assertEquals(0, row.getReturnTotal().compareTo(new BigDecimal("17.50")), "Σ退货(带供应商退库)");
        assertEquals(0, row.getDeductionTotal().compareTo(new BigDecimal("30.00")), "Σ已用抵扣(待抵扣不计)");
        assertEquals(0, row.getBalance().compareTo(new BigDecimal("402.50")), "100+350−17.5−30");
        assertEquals(1, row.getPendingDeductions(), "待抵扣张数提示");

        // 多付 500 → 余额 −97.5 = 预付款
        Account a = account("公式", "1000");
        Long payId = payment(s.getId(), a.getId(), "500", null);
        voucher(payId);
        paymentService.confirm(payId, OP, OPERATOR);
        SettleDtos.SupplierOverviewRow after = overview(s.getId());
        assertEquals(0, after.getBalance().compareTo(new BigDecimal("-97.50")), "402.5−500");
        assertTrue(after.isPrepay(), "负余额显示预付款(§7.3 问2)");
    }

    @Test
    @DisplayName("9·红冲连锁-未付款:采购红冲→结算单作废+已带入抵扣释放回待抵扣+余额归零")
    void redFlushUnpaidBillVoided() {
        Supplier s = supplier("红冲未付供应商", "现结", 0, "0");
        Product p = product("红冲未付商品");
        Long docId = purchase(s.getId(), p.getId(), "100", "3.5", LocalDate.now());
        Long dedId = deduction(s.getId(), "兑换", "50", "会被释放的抵扣");
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), Collections.singletonList(dedId), OP, OPERATOR, ROLE_BOSS);
        assertEquals(DocStatus.PENDING_SETTLE, docHeadMapper.selectById(docId).getDocStatus());

        RedFlushService.Preview preview = redFlushService.preview(docId);
        assertTrue(preview.isExecutable(), "待结算不再拦截(M3-2 通道开通)");
        assertEquals(Boolean.TRUE, preview.getPayableImpact().get("enabled"), "应付影响已启用");

        redFlushService.execute(docId, OP, "整单数量录错", false);

        assertEquals(DocStatus.RED_FLUSHED, docHeadMapper.selectById(docId).getDocStatus());
        assertEquals(SettleBill.ST_VOID, billMapper.selectById(bill.getId()).getBillStatus(), "未付款→结算单作废");
        Deduction released = deductionMapper.selectById(dedId);
        assertEquals(Deduction.ST_PENDING, released.getDedStatus(), "抵扣释放回待抵扣");
        assertNull(released.getUsedSettleBillId());
        assertEquals(0, payableService.balance(s.getId()).compareTo(BigDecimal.ZERO),
                "采购350−红冲350=0,余额归零");
    }

    @Test
    @DisplayName("10·红冲连锁-已付款(§13.2-1):不动已完成付款→生成应付红字;下一单老板确认自动冲抵(offset 回写)")
    void redFlushPaidCreatesRedBillAndOffsetsNext() {
        Supplier s = supplier("红冲已付供应商", "现结", 0, "0");
        Product p = product("红冲已付商品");
        Account a = account("红字", "5000");
        Long docId = purchase(s.getId(), p.getId(), "100", "3.5", LocalDate.now()); // 350
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), null, OP, OPERATOR, ROLE_BOSS);
        Long payId = payment(s.getId(), a.getId(), "350", bill.getId());
        voucher(payId);
        paymentService.confirm(payId, OP, OPERATOR);
        assertEquals(DocStatus.SETTLED, docHeadMapper.selectById(docId).getDocStatus());

        redFlushService.execute(docId, OP, "已付款后发现整单错", false);

        // 不动已完成付款
        assertEquals(Payment.ST_SETTLED, paymentMapper.selectById(payId).getPayStatus(), "已完成付款原样");
        assertEquals(0, accountService.balanceOf(a.getId()).compareTo(new BigDecimal("4650.00")), "钱不回流");
        // 生成红字待冲抵
        SettleBill red = billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSupplierId, s.getId()).eq(SettleBill::getDirection, SettleBill.DIR_RED));
        assertNotNull(red, "生成 settle_bill direction=红字");
        assertEquals(SettleBill.ST_RED_PENDING, red.getBillStatus());
        assertEquals(0, red.getAmountDue().compareTo(new BigDecimal("350.00")), "红字金额=已付款额");
        assertEquals(0, payableService.balance(s.getId()).compareTo(new BigDecimal("-350.00")),
                "余额:0采购−350付款=−350(预付,等红字冲下一单)");

        // 下一单 700:老板确认自动冲抵红字 → 实结 350
        Long doc2 = purchase(s.getId(), p.getId(), "200", "3.5", LocalDate.now());
        SettleBill bill2 = billOf(doc2);
        SettleBillService.ConfirmResult result = settleBillService.confirm(bill2.getId(), null, OP, OPERATOR, ROLE_BOSS);
        assertEquals(0, result.getRedOffsetAmount().compareTo(new BigDecimal("350.00")), "红字自动带入冲抵");
        assertEquals(0, result.getAmountActual().compareTo(new BigDecimal("350.00")), "700−红字350");
        SettleBill redAfter = billMapper.selectById(red.getId());
        assertEquals(SettleBill.ST_RED_USED, redAfter.getBillStatus(), "红字→已冲抵");
        assertEquals(bill2.getId(), redAfter.getOffsetIntoBillId(), "回写冲进哪张单");

        // 付掉下一单实结 350 → 全链归零
        Long pay2 = payment(s.getId(), a.getId(), "350", bill2.getId());
        voucher(pay2);
        paymentService.confirm(pay2, OP, OPERATOR);
        assertEquals(0, payableService.balance(s.getId()).compareTo(BigDecimal.ZERO), "700−350−350=0");
    }

    @Test
    @DisplayName("11·月结逾期黄灯:账期15天已过5天→overdue=true/5天;付清后灯灭")
    void overdueYellowLight() {
        Supplier s = supplier("月结供应商", "月结", 15, "0");
        Product p = product("逾期商品");
        Account a = account("逾期", "5000");
        Long docId = purchase(s.getId(), p.getId(), "10", "3.5", LocalDate.now().minusDays(20));

        SettleDtos.SupplierOverviewRow row = overview(s.getId());
        assertTrue(row.isOverdue(), "到期日=入库-20+15=-5天 → 黄灯");
        assertEquals(5, row.getOverdueDays());

        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), null, OP, OPERATOR, ROLE_BOSS);
        Long payId = payment(s.getId(), a.getId(), "35", bill.getId());
        voucher(payId);
        paymentService.confirm(payId, OP, OPERATOR);
        assertFalse(overview(s.getId()).isOverdue(), "付清后不再亮灯");
    }

    @Test
    @DisplayName("12·对账单:期初+明细(拿货/抵扣/付款/红冲负项行)+期末=实时余额;xlsx 一键导出可解析")
    void statementAndXlsxExport() throws Exception {
        Supplier s = supplier("对账单供应商", "现结", 0, "200");
        Product p = product("对账商品");
        Account a = account("对账", "5000");
        Long docId = purchase(s.getId(), p.getId(), "644", "3.5", LocalDate.now()); // 2254
        Long dedId = deduction(s.getId(), "兑换", "351.63", "厂家已结账");
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), Collections.singletonList(dedId), OP, OPERATOR, ROLE_BOSS);
        Long payId = payment(s.getId(), a.getId(), "1902.37", bill.getId());
        voucher(payId);
        paymentService.confirm(payId, OP, OPERATOR);

        SettleDtos.StatementResp stmt = payableService.statement(s.getId());
        assertEquals(0, stmt.getOpening().compareTo(new BigDecimal("200.00")), "期初");
        assertEquals(3, stmt.getLines().size(), "拿货+抵扣+付款三行");
        assertEquals(0, stmt.getClosing().compareTo(new BigDecimal("200.00")),
                "期末=200+2254−351.63−1902.37=200");
        assertEquals(0, stmt.getClosing().compareTo(payableService.balance(s.getId())), "期末=实时余额同口径");
        // 末行余额=期末
        assertEquals(0, new BigDecimal(String.valueOf(
                        stmt.getLines().get(stmt.getLines().size() - 1).get("balance")))
                .compareTo(stmt.getClosing()));

        byte[] xlsx = payableService.statementXlsx(s.getId());
        assertTrue(xlsx.length > 0);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals("对账单", wb.getSheetName(0));
            assertTrue(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue().contains("对账单供应商"));
        }
    }

    @Test
    @DisplayName("13·P0-1:付款确认时重查结算单——建付款单后采购被红冲(结算单作废)→ 确认被拒,0 流水,已作废单不复活")
    void confirmRejectsWhenBillNoLongerPayable() {
        Supplier s = supplier("复核供应商", "现结", 0, "0");
        Product p = product("复核商品");
        Account a = account("复核", "5000");
        Long docId = purchase(s.getId(), p.getId(), "100", "3.5", LocalDate.now()); // 350
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), null, OP, OPERATOR, ROLE_BOSS); // 待付款
        Long payId = payment(s.getId(), a.getId(), "350", bill.getId());
        voucher(payId);

        // 建付款单之后,采购被红冲 → 结算单连锁作废
        redFlushService.execute(docId, OP, "付款前发现整单错", false);
        assertEquals(SettleBill.ST_VOID, billMapper.selectById(bill.getId()).getBillStatus());

        BizException e = assertThrows(BizException.class, () -> paymentService.confirm(payId, OP, OPERATOR));
        assertTrue(e.getMessage().contains("不能再付款"), "拒付并讲清原因:" + e.getMessage());
        assertEquals(Payment.ST_PENDING, paymentMapper.selectById(payId).getPayStatus(), "付款单原样待付款");
        assertEquals(0, cashFlowMapper.selectCount(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, "付款单").eq(CashFlow::getRefDocId, payId)), "一分钱流水都不落");
        assertEquals(SettleBill.ST_VOID, billMapper.selectById(bill.getId()).getBillStatus(), "已作废单不被复活");
        assertEquals(0, accountService.balanceOf(a.getId()).compareTo(new BigDecimal("5000")), "余额不动");
    }

    @Test
    @DisplayName("14·P1-6:红冲部分付款采购→原结算单关闭(已作废,剩余额不再挂待付款),已付额由红字承接;新付款不能再关联")
    void redFlushPartialPaidClosesOriginalBill() {
        Supplier s = supplier("部分付款供应商", "现结", 0, "0");
        Product p = product("部分付款商品");
        Account a = account("部分付款", "5000");
        Long docId = purchase(s.getId(), p.getId(), "100", "3.5", LocalDate.now()); // 350
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), null, OP, OPERATOR, ROLE_BOSS);
        Long payId = payment(s.getId(), a.getId(), "200", bill.getId()); // 部分付款 → 差异挂起
        voucher(payId);
        paymentService.confirm(payId, OP, OPERATOR);
        assertEquals(SettleBill.ST_DIFF, billMapper.selectById(bill.getId()).getBillStatus());

        redFlushService.execute(docId, OP, "部分付款后发现整单错", false);

        SettleBill closed = billMapper.selectById(bill.getId());
        assertEquals(SettleBill.ST_VOID, closed.getBillStatus(), "原结算单关闭,剩余150不再看似该付");
        assertTrue(closed.getDiffNote().contains("红字"), "diff_note 说明已付部分由红字承接:" + closed.getDiffNote());
        SettleBill red = billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSupplierId, s.getId()).eq(SettleBill::getDirection, SettleBill.DIR_RED));
        assertNotNull(red);
        assertEquals(0, red.getAmountDue().compareTo(new BigDecimal("200.00")), "红字=已付额");
        // 已作废单拒绝再关联新付款
        BizException e = assertThrows(BizException.class,
                () -> payment(s.getId(), a.getId(), "150", bill.getId()));
        assertTrue(e.getMessage().contains("待付款/差异挂起"), e.getMessage());
    }

    @Test
    @DisplayName("15·M3-9七律修复:付款单作废——仅待付款可作废(备注强制);已付款拒作废指路红冲;作废后不能再确认")
    void voidPendingPayment() {
        Supplier s = supplier("作废供应商", "现结", 0, "0");
        Account a = account("作废", "5000");
        Long payId = payment(s.getId(), a.getId(), "100", null);

        // 备注强制
        BizException e0 = assertThrows(BizException.class,
                () -> paymentService.voidPayment(payId, " ", OP, OPERATOR));
        assertTrue(e0.getMessage().contains("备注"), e0.getMessage());

        paymentService.voidPayment(payId, "录错供应商,重录", OP, OPERATOR);
        assertEquals(Payment.ST_VOID, paymentMapper.selectById(payId).getPayStatus(), "待付款→已作废(留痕不删)");

        // 作废后不能再确认(不再永远躺在列表更不能复活)
        voucher(payId);
        BizException e1 = assertThrows(BizException.class, () -> paymentService.confirm(payId, OP, OPERATOR));
        assertTrue(e1.getMessage().contains("仅[待付款]"), e1.getMessage());

        // 已付款(钱已动)拒作废,指路红冲
        Long paidId = payment(s.getId(), a.getId(), "50", null);
        voucher(paidId);
        paymentService.confirm(paidId, OP, OPERATOR);
        BizException e2 = assertThrows(BizException.class,
                () -> paymentService.voidPayment(paidId, "想删掉", OP, OPERATOR));
        assertTrue(e2.getMessage().contains("红冲"), "钱已动的唯一逆向=红冲:" + e2.getMessage());
    }

    @Test
    @DisplayName("16·M3-9七律修复:付款红冲全链——负额红冲行+退款流水回账户+应付余额恢复+结算单回待付款;双红冲被拒")
    void redFlushPaidPayment() {
        Supplier s = supplier("红冲付款供应商", "现结", 0, "0");
        Product p = product("红冲付款商品");
        Account a = account("红冲付款", "5000");
        Long docId = purchase(s.getId(), p.getId(), "100", "3.5", LocalDate.now()); // 350
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), null, OP, OPERATOR, ROLE_BOSS);
        Long payId = payment(s.getId(), a.getId(), "350", bill.getId());
        voucher(payId);
        paymentService.confirm(payId, OP, OPERATOR); // 自动核销闭环
        assertEquals(SettleBill.ST_DONE, billMapper.selectById(bill.getId()).getBillStatus());
        assertEquals(0, accountService.balanceOf(a.getId()).compareTo(new BigDecimal("4650")), "5000−350");
        assertEquals(0, overview(s.getId()).getBalance().compareTo(BigDecimal.ZERO), "应付已清");

        // 备注强制
        BizException e0 = assertThrows(BizException.class,
                () -> paymentService.redFlush(payId, "", OP, OPERATOR));
        assertTrue(e0.getMessage().contains("备注"), e0.getMessage());

        Long redId = paymentService.redFlush(payId, "付错了:这笔该付蔡彩云", OP, OPERATOR);

        // 原单已红冲(历史不改写),红冲行=负额承接
        assertEquals(Payment.ST_RED_FLUSHED, paymentMapper.selectById(payId).getPayStatus());
        Payment red = paymentMapper.selectById(redId);
        assertEquals(Payment.ST_RED, red.getPayStatus());
        assertEquals(payId, red.getRedFlushOf(), "红冲行回链原单");
        assertEquals(0, red.getAmount().compareTo(new BigDecimal("-350.00")), "负额红冲行");
        assertTrue(red.getPayNo().startsWith("FKR-"), "红冲单号 FKR- 前缀:" + red.getPayNo());

        // 退款流水:钱按原路回账户(收 350),账户余额恢复
        CashFlow refund = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, "付款单").eq(CashFlow::getRefDocId, redId).last("LIMIT 1"));
        assertNotNull(refund, "红冲落退款流水(钱只能被单据改)");
        assertEquals("收", refund.getDirection());
        assertEquals(0, refund.getAmount().compareTo(new BigDecimal("350.00")));
        assertEquals("供应商付款", refund.getCategory());
        assertEquals(0, accountService.balanceOf(a.getId()).compareTo(new BigDecimal("5000")), "账户余额恢复 5000");

        // Σ已付款自动回落 → 应付余额恢复 350;结算单回待付款可重新核销
        assertEquals(0, overview(s.getId()).getBalance().compareTo(new BigDecimal("350.00")), "应付恢复(红字承接不改写历史)");
        SettleBill restored = billMapper.selectById(bill.getId());
        assertEquals(SettleBill.ST_PENDING_PAY, restored.getBillStatus(), "结算单 已完成→待付款");
        assertTrue(restored.getDiffNote().contains("已红冲"), "留痕:" + restored.getDiffNote());

        // 一张单只能红冲一次;红冲行自身不能再红冲
        BizException e1 = assertThrows(BizException.class,
                () -> paymentService.redFlush(payId, "再冲一次", OP, OPERATOR));
        assertTrue(e1.getMessage().contains("仅[已付款/结算完成/差异挂起]") || e1.getMessage().contains("红冲过"), e1.getMessage());
        BizException e2 = assertThrows(BizException.class,
                () -> paymentService.redFlush(redId, "冲红冲行", OP, OPERATOR));
        assertTrue(e2.getMessage().contains("红冲行"), e2.getMessage());

        // 逾期 aging 只读口跑通(P1-5 数据口)
        assertNotNull(payableService.payableAging());
    }
}
