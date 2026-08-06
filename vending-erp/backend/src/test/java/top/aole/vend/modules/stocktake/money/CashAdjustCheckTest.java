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

    private void settleBill(Long supplierId, String direction, String amountActual, Long sourceDocId) {
        jdbc.update("INSERT INTO yc_vend_settle_bill (bill_no, bill_type, direction, supplier_id, " +
                        "source_doc_id, amount_due, amount_actual, bill_status) VALUES (?,?,?,?,?,?,?,?)",
                "JS-QP-" + SEQ.incrementAndGet(), "应付", direction, supplierId,
                sourceDocId, amountActual, amountActual, "待付款");
    }

    private void payment(Long supplierId, Long accountId, String amount, String payStatus) {
        jdbc.update("INSERT INTO yc_vend_payment (pay_no, supplier_id, account_id, amount, pay_status) " +
                        "VALUES (?,?,?,?,?)",
                "FK-QP-" + SEQ.incrementAndGet(), supplierId, accountId, amount, payStatus);
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

        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, OPERATOR);
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

    // ============================== ⑧ 应付核对+两出口 ==============================

    @Test
    @DisplayName("应付核对:系统数=期初+Σ正常−Σ红字−Σ已付款;不符出口两按钮=补录(跳采购页)/红冲(跳来源单据),留痕后才许收口")
    void payableCheckComputesBalanceAndTwoExits() {
        Long payAccId = account("银行卡", "1000");
        Supplier a = supplier("蔡彩云-测试", "0");
        Supplier b = supplier("陈老板-测试", "0");
        settleBill(a.getId(), "正常", "500.00", 777L);
        settleBill(a.getId(), "红字", "50.00", null);
        payment(a.getId(), payAccId, "100.00", "已付款");
        payment(a.getId(), payAccId, "999.00", "待付款"); // 未付的不扣
        settleBill(b.getId(), "正常", "200.00", 888L);

        Long checkId = cashCheckService.start(OP, OPERATOR);
        CashMoneyDtos.CheckDetailResp detail = cashCheckService.detail(checkId);
        assertEquals(2, detail.getPayableItems().size(), "有往来的供应商全部进应付核对");
        CashMoneyDtos.CheckItemRow rowA = detail.getPayableItems().stream()
                .filter(r -> r.getRefId().equals(a.getId())).findFirst().orElseThrow(AssertionError::new);
        CashMoneyDtos.CheckItemRow rowB = detail.getPayableItems().stream()
                .filter(r -> r.getRefId().equals(b.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, rowA.getSystemAmount().compareTo(new BigDecimal("350.00")),
                "A 应付=500−50红字−100已付=350(待付款不扣)");
        assertEquals(0, rowB.getSystemAmount().compareTo(new BigDecimal("200.00")));
        assertEquals(888L, rowB.getSourceDocId(), "红冲跳转目标=最近正常结算单的来源单据");

        // 录对方账:A 330(差 −20),B 180(差 −20);账户行也补齐(完成守卫要求)
        CashMoneyDtos.SaveActualsReq save = new CashMoneyDtos.SaveActualsReq();
        for (Object[] pair : new Object[][]{{rowA.getId(), "330"}, {rowB.getId(), "180"}}) {
            CashMoneyDtos.ActualRow r = new CashMoneyDtos.ActualRow();
            r.setItemId((Long) pair[0]);
            r.setActualAmount(new BigDecimal((String) pair[1]));
            save.getRows().add(r);
        }
        CashMoneyDtos.ActualRow bank = new CashMoneyDtos.ActualRow();
        bank.setItemId(detail.getAccountItems().get(0).getId());
        bank.setActualAmount(new BigDecimal("1000"));
        save.getRows().add(bank);
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
        assertEquals(888L, redFlush.getSourceDocId());
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
