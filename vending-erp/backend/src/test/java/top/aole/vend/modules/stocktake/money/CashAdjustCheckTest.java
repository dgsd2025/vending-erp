package top.aole.vend.modules.stocktake.money;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.stocktake.money.domain.entity.CashCheckItem;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos;
import top.aole.vend.modules.stocktake.money.service.CashAdjustService;
import top.aole.vend.modules.stocktake.money.service.CashCheckService;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3-5 集成测试(vend_test_cashadj 独立库,每例事务回滚):
 * 资金调整单(P1-7 钱盘差异唯一出口)+ 月度钱盘三核对(§8.2 D2)。
 *
 * 覆盖验收清单:①调整单确认→cash_flow 落流水+余额修正 ②虚拟账户拒绝
 * ③老板角色守卫 ④双击抢占只过账一次 ⑤原因枚举规则(其他必备注/方向约束)
 * ⑥核对记录落库+差异计算+一键生成调整单防重复+完成守卫
 * ⑦结算模式 UNSET 平台核对跳过 ⑧应付核对口径+补录/红冲两出口。
 */
@ActiveProfiles("test-cashadj")
class CashAdjustCheckTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final String OPERATOR = "钱盘测试员";
    private static final String BOSS = "老板";

    @Autowired
    private AccountService accountService;
    @Autowired
    private CashAdjustService cashAdjustService;
    @Autowired
    private CashCheckService cashCheckService;
    @Autowired
    private SettleModeService settleModeService;
    @Autowired
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper productMapper;
    @Autowired
    private top.aole.vend.modules.settle.mapper.SettleBillMapper settleBillMapper;
    @Autowired
    private top.aole.vend.modules.settle.service.SettleBillService settleBillService;
    @Autowired
    private top.aole.vend.modules.settle.service.PaymentService paymentService;
    @Autowired
    private top.aole.vend.modules.settle.service.PayableService payableService;
    @Autowired
    private top.aole.vend.modules.money.service.AttachmentService attachmentService;

    // ============================== 造数工具 ==============================

    private Long account(String type, String opening) {
        MoneyDtos.AccountCreateReq req = new MoneyDtos.AccountCreateReq();
        req.setAccountName("钱盘" + type + SEQ.incrementAndGet());
        req.setAccountType(type);
        if (opening != null) {
            req.setOpeningBalance(new BigDecimal(opening));
        }
        return accountService.create(req, OP, OPERATOR);
    }

    private Long adjust(Long accountId, String signedAmount, String reason, String remark) {
        CashMoneyDtos.AdjustCreateReq req = new CashMoneyDtos.AdjustCreateReq();
        req.setAccountId(accountId);
        req.setAdjustAmount(new BigDecimal(signedAmount));
        req.setReason(reason);
        req.setRemark(remark);
        return cashAdjustService.create(req, OP, OPERATOR);
    }

    private List<CashFlow> flowsOf(Long docId) {
        return cashFlowMapper.selectList(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, CashAdjustService.REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, docId));
    }

    private Supplier supplier(String name, String openingPayable) {
        Supplier s = new Supplier();
        s.setSupplierCode("QP" + SEQ.incrementAndGet());
        s.setSupplierName(name);
        s.setOpeningPayable(openingPayable == null ? BigDecimal.ZERO : new BigDecimal(openingPayable));
        supplierMapper.insert(s);
        return s;
    }

    /** 采购入库(带供应商)建单→提交→确认:触发结算单自动生成(真实链路,M3-9 P1-2 单口径) */
    private Long purchase(Long supplierId, Long productId, String qty, String price) {
        top.aole.vend.modules.doc.dto.DocCreateReq r = req(DocType.PURCHASE_IN, null,
                top.aole.vend.modules.doc.service.DocService.SOURCE_MANUAL, java.time.LocalDate.now(),
                new Object[]{productId, qty, price});
        r.setSupplierId(supplierId);
        Long id = docService.createDoc(r, OP);
        docService.submit(id, OP);
        docService.confirm(id, OP, false, null);
        return id;
    }

    private Long productId(String name) {
        top.aole.vend.modules.basedata.domain.entity.Product p =
                new top.aole.vend.modules.basedata.domain.entity.Product();
        p.setSkuCode("QPP" + SEQ.incrementAndGet());
        p.setProductName(name);
        p.setUnit("件");
        p.setProductStatus("在售");
        productMapper.insert(p);
        return p.getId();
    }

    private top.aole.vend.modules.settle.domain.entity.SettleBill billOf(Long docId) {
        return settleBillMapper.selectOne(new LambdaQueryWrapper<top.aole.vend.modules.settle.domain.entity.SettleBill>()
                .eq(top.aole.vend.modules.settle.domain.entity.SettleBill::getSourceDocId, docId)
                .eq(top.aole.vend.modules.settle.domain.entity.SettleBill::getDirection, "正常").last("LIMIT 1"));
    }

    private Long confirmedPayment(Long supplierId, Long accountId, String amount, Long billId) {
        top.aole.vend.modules.settle.dto.SettleDtos.PaymentCreateReq req =
                new top.aole.vend.modules.settle.dto.SettleDtos.PaymentCreateReq();
        req.setSupplierId(supplierId);
        req.setAccountId(accountId);
        req.setAmount(new BigDecimal(amount));
        req.setSettleBillId(billId);
        Long payId = paymentService.create(req, OP, OPERATOR);
        attachmentService.upload("payment", payId, "转账截图", "转账.png",
                "png".getBytes(java.nio.charset.StandardCharsets.UTF_8), OP, OPERATOR);
        paymentService.confirm(payId, OP, OPERATOR);
        return payId;
    }

    // ============================== ① 调整单过账 ==============================

    @Test
    @DisplayName("资金调整单确认(老板)→ cash_flow 落一条(category=资金调整/pl_line=不进利润表)→ 余额=期初+Σ流水自然修正,单据已完成")
    void adjustConfirmPostsCashFlowAndFixesBalance() {
        Long accId = account("现金", "100");
        Long docId = adjust(accId, "-13.20", "盘亏", "钱盘发现现金差 −13.2");
        assertEquals(DocStatus.PENDING_CONFIRM, docHeadMapper.selectById(docId).getDocStatus(),
                "建单即提交,停在待确认等老板");
        assertTrue(docHeadMapper.selectById(docId).getDocNo().startsWith("ZJ-"), "复用 doc 通道:单号前缀 ZJ");

        cashAdjustService.confirm(docId, OP, BOSS, OPERATOR);

        List<CashFlow> flows = flowsOf(docId);
        assertEquals(1, flows.size(), "确认后恰好一条流水");
        CashFlow flow = flows.get(0);
        assertEquals("资金调整", flow.getCategory());
        assertEquals("不进利润表", flow.getPlLine(), "资金调整只动资产不动损益(附录D)");
        assertEquals(CashFlow.DIR_OUT, flow.getDirection());
        assertEquals(0, flow.getAmount().compareTo(new BigDecimal("13.20")));
        assertEquals(0, accountService.balanceOf(accId).compareTo(new BigDecimal("86.80")),
                "余额=期初100−13.2=86.8(没有任何直改余额的路径)");
        assertEquals(DocStatus.COMPLETED, docHeadMapper.selectById(docId).getDocStatus(),
                "过账即闭环:单据自动完成");
    }

    // ============================== ② 虚拟账户拒绝 ==============================

    @Test
    @DisplayName("虚拟账户(平台待结算)不可手工收支:建单即拦;真实账户放行")
    void virtualAccountRejectedAtCreate() {
        Long virtualId = account("平台待结算", null);
        BizException e = assertThrows(BizException.class,
                () -> adjust(virtualId, "-10", "盘亏", null));
        assertTrue(e.getMessage().contains("不可手工收支"), "报错讲清规则:" + e.getMessage());

        Long realId = account("微信", "50");
        assertNotNull(adjust(realId, "-10", "盘亏", null), "真实账户放行");
    }

    // ============================== ③ 老板守卫 ==============================

    @Test
    @DisplayName("老板确认守卫:无角色/员工角色确认被拒(不落流水,单据停在待确认);老板放行")
    void bossRoleGuardOnConfirm() {
        Long accId = account("现金", "100");
        Long docId = adjust(accId, "5.00", "盘盈", null);

        BizException noRole = assertThrows(BizException.class,
                () -> cashAdjustService.confirm(docId, OP, null, OPERATOR));
        assertTrue(noRole.getMessage().contains("限老板角色"), noRole.getMessage());
        BizException staff = assertThrows(BizException.class,
                () -> cashAdjustService.confirm(docId, OP, "员工", OPERATOR));
        assertTrue(staff.getMessage().contains("限老板角色"), staff.getMessage());

        assertEquals(0, flowsOf(docId).size(), "被拒时一条流水都不许落");
        assertEquals(DocStatus.PENDING_CONFIRM, docHeadMapper.selectById(docId).getDocStatus());

        cashAdjustService.confirm(docId, OP, BOSS, OPERATOR);
        assertEquals(1, flowsOf(docId).size());
    }

    // ============================== ④ 双击抢占 ==============================

    @Test
    @DisplayName("双击/重复确认抢占(DocStatusGuard):第二次确认被拒,流水仍只有一条(财务单绝不过账两遍)")
    void doubleClickGuardOnlyOnePosting() {
        Long accId = account("现金", "100");
        Long docId = adjust(accId, "-13.20", "盘亏", null);
        cashAdjustService.confirm(docId, OP, BOSS, OPERATOR);

        BizException second = assertThrows(BizException.class,
                () -> cashAdjustService.confirm(docId, OP, BOSS, OPERATOR));
        assertTrue(second.getMessage().contains("不允许从") || second.getMessage().contains("已被他人处理"),
                "后到者人话报错:" + second.getMessage());
        assertEquals(1, flowsOf(docId).size(), "流水仍只有一条");
        assertEquals(0, accountService.balanceOf(accId).compareTo(new BigDecimal("86.80")),
                "余额只扣了一次");
    }

    // ============================== ⑤ 原因枚举规则 ==============================

    @Test
    @DisplayName("原因枚举规则:金额0拒/盘盈只能收/盘亏只能支/其他必备注/非法原因拒")
    void reasonRulesEnforced() {
        Long accId = account("现金", "100");
        assertThrows(BizException.class, () -> adjust(accId, "0", "盘亏", null), "金额0拒绝");
        assertTrue(assertThrows(BizException.class, () -> adjust(accId, "-5", "盘盈", null))
                .getMessage().contains("只能是「收」"), "盘盈填负数被拦");
        assertTrue(assertThrows(BizException.class, () -> adjust(accId, "5", "手续费漏记", null))
                .getMessage().contains("只能是「支」"), "手续费漏记填正数被拦");
        assertTrue(assertThrows(BizException.class, () -> adjust(accId, "-5", "其他", " "))
                .getMessage().contains("备注"), "其他必备注");
        assertThrows(BizException.class, () -> adjust(accId, "-5", "随便写的"), "非法原因拒绝");
        assertNotNull(adjust(accId, "8.88", "期初错", "期初当时少数了一张红票"), "期初错两个方向都行");
    }

    private Long adjust(Long accountId, String signedAmount, String reason) {
        return adjust(accountId, signedAmount, reason, null);
    }

    // ============================== ⑥ 核对记录+差异+一键生成 ==============================

    @Test
    @DisplayName("钱盘核对:快照账户系统数→录实际→差异=实际−系统落库→一键生成调整单(防重复)→老板确认修余额→完成守卫")
    void checkRecordSnapshotDiffGenAdjustAndFinishGuard() {
        Long cashId = account("现金", "100");
        Long wechatId = account("微信", "300");

        Long checkId = cashCheckService.start(OP, OPERATOR);
        CashMoneyDtos.CheckDetailResp detail = cashCheckService.detail(checkId);
        assertEquals("进行中", detail.getCheckStatus());
        assertEquals(2, detail.getAccountItems().size(), "两个真实账户全部进核对");
        CashMoneyDtos.CheckItemRow cashRow = detail.getAccountItems().stream()
                .filter(r -> r.getRefId().equals(cashId)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, cashRow.getSystemAmount().compareTo(new BigDecimal("100")), "系统数=期初+Σ流水快照");

        // 录实际:现金实数 86.8(差 −13.2),微信相符
        CashMoneyDtos.SaveActualsReq save = new CashMoneyDtos.SaveActualsReq();
        CashMoneyDtos.ActualRow r1 = new CashMoneyDtos.ActualRow();
        r1.setItemId(cashRow.getId());
        r1.setActualAmount(new BigDecimal("86.80"));
        CashMoneyDtos.ActualRow r2 = new CashMoneyDtos.ActualRow();
        r2.setItemId(detail.getAccountItems().stream().filter(r -> r.getRefId().equals(wechatId))
                .findFirst().orElseThrow(AssertionError::new).getId());
        r2.setActualAmount(new BigDecimal("300"));
        save.getRows().add(r1);
        save.getRows().add(r2);
        cashCheckService.saveActuals(checkId, save, OP, OPERATOR);

        detail = cashCheckService.detail(checkId);
        CashMoneyDtos.CheckItemRow diffRow = detail.getAccountItems().stream()
                .filter(r -> r.getRefId().equals(cashId)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, diffRow.getDiffAmount().compareTo(new BigDecimal("-13.20")), "差异=实际−系统");

        // 差异行没走出口 → 完成被守卫拦下
        assertTrue(assertThrows(BizException.class,
                        () -> cashCheckService.finish(checkId, OP, OPERATOR))
                .getMessage().contains("资金调整单"), "完成守卫指向唯一出口");

        // 一键生成调整单(盘亏 −13.2)→ 回链;重复生成被拒
        Long adjustDocId = cashCheckService.genAdjust(checkId, diffRow.getId(), OP, OPERATOR);
        assertEquals(DocType.CASH_ADJUST, docHeadMapper.selectById(adjustDocId).getDocType());
        assertEquals(adjustDocId, cashCheckService.detail(checkId).getAccountItems().stream()
                .filter(r -> r.getRefId().equals(cashId)).findFirst().orElseThrow(AssertionError::new)
                .getAdjustDocId(), "调整单回链核对行");
        assertTrue(assertThrows(BizException.class,
                        () -> cashCheckService.genAdjust(checkId, diffRow.getId(), OP, OPERATOR))
                .getMessage().contains("不许重复生成"), "防重复生成");

        // 老板确认 → 余额修正到实盘
        cashAdjustService.confirm(adjustDocId, OP, BOSS, OPERATOR);
        assertEquals(0, accountService.balanceOf(cashId).compareTo(new BigDecimal("86.80")));
        CashMoneyDtos.AdjustRow adjustRow = cashAdjustService.detail(adjustDocId);
        assertEquals("盘亏", adjustRow.getReason());
        assertEquals(checkId, adjustRow.getCashCheckId(), "调整单记来源核对记录");

        // 出口走完 → 完成收口;再次完成被拒(条件更新防双击)
        cashCheckService.finish(checkId, OP, OPERATOR);
        assertEquals("已完成", cashCheckService.detail(checkId).getCheckStatus());
        assertThrows(BizException.class, () -> cashCheckService.finish(checkId, OP, OPERATOR));
    }

    // ============================== ⑦ 平台核对跳过 ==============================

    @Test
    @DisplayName("平台到账核对:结算模式 UNSET → 整块跳过+横幅原文留档;定型 PLATFORM 后虚账进核对且不许走调整单")
    void settleModeGatesPlatformSection() {
        account("现金", "10"); // 至少一行账户核对
        Long checkId = cashCheckService.start(OP, OPERATOR);
        CashMoneyDtos.CheckDetailResp detail = cashCheckService.detail(checkId);
        assertTrue(Boolean.TRUE.equals(detail.getPlatformSkipped()), "UNSET → 平台核对跳过");
        assertTrue(detail.getPlatformNote().contains("结算模式待核实"), "横幅原文留档");
        assertTrue(detail.getPlatformItems().isEmpty());
        cashCheckService.cancel(checkId, OP, OPERATOR);

        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR, "老板");
        Long virtualId = account("平台待结算", null);
        Long checkId2 = cashCheckService.start(OP, OPERATOR);
        CashMoneyDtos.CheckDetailResp detail2 = cashCheckService.detail(checkId2);
        assertFalse(Boolean.TRUE.equals(detail2.getPlatformSkipped()), "定型后不再跳过");
        assertEquals(1, detail2.getPlatformItems().size(), "平台待结算虚账进核对留档");

        // 平台行手填差异后也不许走资金调整单(虚账差异走结算单 M3-4)
        CashMoneyDtos.CheckItemRow platformRow = detail2.getPlatformItems().get(0);
        assertEquals(virtualId, platformRow.getRefId());
        CashMoneyDtos.SaveActualsReq save = new CashMoneyDtos.SaveActualsReq();
        CashMoneyDtos.ActualRow row = new CashMoneyDtos.ActualRow();
        row.setItemId(platformRow.getId());
        row.setActualAmount(new BigDecimal("999"));
        save.getRows().add(row);
        cashCheckService.saveActuals(checkId2, save, OP, OPERATOR);
        assertTrue(assertThrows(BizException.class,
                        () -> cashCheckService.genAdjust(checkId2, platformRow.getId(), OP, OPERATOR))
                .getMessage().contains("平台结算单"), "虚账差异指到结算单出口");
    }

    // ============================== ⑦.5 平台行真值(M3-9 P1-1) ==============================

    @Test
    @DisplayName("P1-1:PLATFORM 模式钱盘平台行系统数=待结算真值(sale_record 口径,与结算单/总览同源),不再用恒为期初的虚账流水余额")
    void platformRowUsesPendingBalanceTruth() {
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR, "老板");
        account("现金", "10");
        Long virtualId = account("平台待结算", null);
        // 两笔未回填正常销售(insertSale 实收各 1 元)→ 待结算真值 = 2;虚账流水余额仍是 0(无写入方)
        insertSale(null, null, "1", "正常", java.time.LocalDateTime.now());
        insertSale(null, null, "1", "正常", java.time.LocalDateTime.now());
        assertEquals(0, accountService.balanceOf(virtualId).compareTo(BigDecimal.ZERO),
                "虚账流水余额恒为期初(全系统无人往虚账写流水)——这正是老口径的错");

        Long checkId = cashCheckService.start(OP, OPERATOR);
        CashMoneyDtos.CheckDetailResp detail = cashCheckService.detail(checkId);
        assertEquals(1, detail.getPlatformItems().size());
        assertEquals(0, detail.getPlatformItems().get(0).getSystemAmount().compareTo(new BigDecimal("2.00")),
                "平台行系统数=待结算真值 2(SettlementQueryMapper.pendingBalance 同源口径)");
        cashCheckService.cancel(checkId, OP, OPERATOR);
    }

    // ============================== ⑧ 应付核对+两出口 ==============================

    @Test
    @DisplayName("应付核对(M3-9 P1-2 单一口径):系统数=PayableService §7.3 公式(与 p8 供应商卡同一实现,两口径断言相等);不符出口两按钮=补录/红冲,留痕后才许收口")
    void payableCheckComputesBalanceAndTwoExits() {
        Long payAccId = account("银行卡", "1000");
        Supplier a = supplier("蔡彩云-测试", "0");
        Supplier b = supplier("陈老板-测试", "0");
        // A:真实链路 采购350 → 老板复核 → 部分付款100(差异挂起);再挂一张未确认付款999(不扣)
        Long docA = purchase(a.getId(), productId("钱盘应付商品A"), "100", "3.5"); // 350
        settleBillService.confirm(billOf(docA).getId(), null, OP, OPERATOR, "老板");
        confirmedPayment(a.getId(), payAccId, "100", billOf(docA).getId());
        top.aole.vend.modules.settle.dto.SettleDtos.PaymentCreateReq pending =
                new top.aole.vend.modules.settle.dto.SettleDtos.PaymentCreateReq();
        pending.setSupplierId(a.getId());
        pending.setAccountId(payAccId);
        pending.setAmount(new BigDecimal("999"));
        paymentService.create(pending, OP, OPERATOR); // 未确认:pay_time 空,不进应付公式
        // B:采购200,结算单停在待确认
        Long docB = purchase(b.getId(), productId("钱盘应付商品B"), "100", "2"); // 200

        Long checkId = cashCheckService.start(OP, OPERATOR);
        CashMoneyDtos.CheckDetailResp detail = cashCheckService.detail(checkId);
        assertEquals(2, detail.getPayableItems().size(), "有往来的供应商全部进应付核对");
        CashMoneyDtos.CheckItemRow rowA = detail.getPayableItems().stream()
                .filter(r -> r.getRefId().equals(a.getId())).findFirst().orElseThrow(AssertionError::new);
        CashMoneyDtos.CheckItemRow rowB = detail.getPayableItems().stream()
                .filter(r -> r.getRefId().equals(b.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, rowA.getSystemAmount().compareTo(new BigDecimal("250.00")),
                "A 应付=350−100已付=250(未确认付款不扣)");
        assertEquals(0, rowB.getSystemAmount().compareTo(new BigDecimal("200.00")));
        // 两口径断言相等(P1-2 验收):钱盘应付行 ≡ p8 供应商卡 PayableService.balance
        assertEquals(0, rowA.getSystemAmount().compareTo(payableService.balance(a.getId())),
                "钱盘应付与供应商卡必须同一个数(单一真相源)");
        assertEquals(0, rowB.getSystemAmount().compareTo(payableService.balance(b.getId())));
        assertEquals(docB, rowB.getSourceDocId(), "红冲跳转目标=最近正常结算单的来源单据");

        // 录对方账:A 230(差 −20),B 180(差 −20);账户行也补齐(完成守卫要求)
        CashMoneyDtos.SaveActualsReq save = new CashMoneyDtos.SaveActualsReq();
        for (Object[] pair : new Object[][]{{rowA.getId(), "230"}, {rowB.getId(), "180"}}) {
            CashMoneyDtos.ActualRow r = new CashMoneyDtos.ActualRow();
            r.setItemId((Long) pair[0]);
            r.setActualAmount(new BigDecimal((String) pair[1]));
            save.getRows().add(r);
        }
        for (CashMoneyDtos.CheckItemRow acc : detail.getAccountItems()) {
            CashMoneyDtos.ActualRow accRow = new CashMoneyDtos.ActualRow();
            accRow.setItemId(acc.getId());
            accRow.setActualAmount(acc.getSystemAmount()); // 账户实数按系统数填平(本例只考应付差异)
            save.getRows().add(accRow);
        }
        cashCheckService.saveActuals(checkId, save, OP, OPERATOR);

        // 差异行没走出口 → 完成被拦,报错指到两按钮
        assertTrue(assertThrows(BizException.class,
                        () -> cashCheckService.finish(checkId, OP, OPERATOR))
                .getMessage().contains("补录"), "完成守卫指向补录/红冲出口");

        // 出口1:补录 → 返采购页路由(付款/抵扣录入所在地,只跳转不重造)
        CashMoneyDtos.PayableExitResp backfill = cashCheckService.markPayableExit(
                checkId, rowA.getId(), CashCheckItem.EXIT_BACKFILL, OP, OPERATOR);
        assertEquals(CashCheckService.BACKFILL_ROUTE, backfill.getRoute());
        // 出口2:红冲 → 返来源单据 id(前端打开单据抽屉走既有红冲入口)
        CashMoneyDtos.PayableExitResp redFlush = cashCheckService.markPayableExit(
                checkId, rowB.getId(), CashCheckItem.EXIT_RED_FLUSH, OP, OPERATOR);
        assertEquals(docB, redFlush.getSourceDocId());
        // 非法出口拒绝
        assertThrows(BizException.class, () -> cashCheckService.markPayableExit(
                checkId, rowA.getId(), "改余额", OP, OPERATOR));

        cashCheckService.finish(checkId, OP, OPERATOR);
        CashMoneyDtos.CheckDetailResp done = cashCheckService.detail(checkId);
        assertEquals("已完成", done.getCheckStatus());
        assertEquals(CashCheckItem.EXIT_BACKFILL, done.getPayableItems().stream()
                .filter(r -> r.getRefId().equals(a.getId())).findFirst().orElseThrow(AssertionError::new)
                .getExitAction(), "出口选择留痕归档");
    }
}
