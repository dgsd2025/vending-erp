package top.aole.vend.modules.claim;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.claim.domain.entity.Claim;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.mapper.ClaimMapper;
import top.aole.vend.modules.claim.service.ClaimService;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.expense.domain.entity.Equipment;
import top.aole.vend.modules.expense.domain.entity.Expense;
import top.aole.vend.modules.expense.dto.ExpenseDtos;
import top.aole.vend.modules.expense.mapper.EquipmentMapper;
import top.aole.vend.modules.expense.mapper.ExpenseMapper;
import top.aole.vend.modules.expense.service.ExpenseService;
import top.aole.vend.modules.expense.service.OfflineSaleService;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.domain.enums.PlLine;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.report.service.CostEngine;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.mapper.StocktakeItemMapper;
import top.aole.vend.modules.stocktake.mapper.StocktakeMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3-4 集成测试(vend_test_claim 独立库,每例事务回滚):
 * 索赔生命周期(申请中计应收/到账落流水/放弃退出应收)/ 凭证门禁 / 净损耗=损耗−已获赔 /
 * 支出落流水 / 买设备进台账 / 线下复合单三件套原子生成 / 线下不入待结算口径 / 盘亏行回填 claim_id。
 */
@ActiveProfiles("test-claim")
class ClaimExpenseOfflineTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final String OPERATOR = "M34测试员";

    @Autowired
    private ClaimService claimService;
    @Autowired
    private ClaimMapper claimMapper;
    @Autowired
    private ExpenseService expenseService;
    @Autowired
    private ExpenseMapper expenseMapper;
    @Autowired
    private EquipmentMapper equipmentMapper;
    @Autowired
    private OfflineSaleService offlineSaleService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private CostEngine costEngine;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private MachineMapper machineMapper;
    @Autowired
    private StocktakeMapper stocktakeMapper;
    @Autowired
    private StocktakeItemMapper stocktakeItemMapper;

    // ============================== 造数工具 ==============================

    private Long account(String type, String opening) {
        MoneyDtos.AccountCreateReq req = new MoneyDtos.AccountCreateReq();
        req.setAccountName("M34" + type + SEQ.incrementAndGet());
        req.setAccountType(type);
        if (opening != null) {
            req.setOpeningBalance(new BigDecimal(opening));
        }
        return accountService.create(req, OP, OPERATOR);
    }

    private Product product(String name) {
        Product p = new Product();
        p.setSkuCode("M34" + SEQ.incrementAndGet());
        p.setProductName(name);
        p.setUnit("瓶");
        p.setProductStatus("在售");
        productMapper.insert(p);
        return p;
    }

    private Machine machine(String name) {
        Machine m = new Machine();
        long n = SEQ.incrementAndGet();
        m.setMachineCode("M34M" + n);
        m.setMachineName(name);
        m.setDeviceId("M34DEV" + n);
        m.setMachineStatus("在线");
        machineMapper.insert(m);
        return m;
    }

    private Long claim(String target, String amount) {
        ClaimDtos.CreateReq req = new ClaimDtos.CreateReq();
        req.setClaimTarget(target);
        req.setAmount(new BigDecimal(amount));
        return claimService.create(req, OP, OPERATOR);
    }

    private void voucher(String refType, Long refId) {
        attachmentService.upload(refType, refId, "赔付凭证".equals(refType) ? "赔付凭证" : "转账截图",
                "凭证.png", "fake-png".getBytes(StandardCharsets.UTF_8), OP, OPERATOR);
    }

    private void claimVoucher(Long claimId) {
        attachmentService.upload(ClaimService.ATTACH_REF_TYPE, claimId, "赔付凭证",
                "厂家赔付.png", "fake-png".getBytes(StandardCharsets.UTF_8), OP, OPERATOR);
    }

    private void expenseVoucher(Long expenseId) {
        attachmentService.upload(ExpenseService.ATTACH_REF_TYPE, expenseId, "转账截图",
                "付款截图.png", "fake-png".getBytes(StandardCharsets.UTF_8), OP, OPERATOR);
    }

    // ============================== ① 索赔:申请中计应收 ==============================

    @Test
    @DisplayName("索赔应收 = Σ申请中金额(资产快照 M3-6 取数口):两张申请中合计;到账/放弃各自退出")
    void receivableIsSumOfPending() {
        BigDecimal base = claimService.receivable();
        Long c1 = claim("厂家", "500.00");
        Long c2 = claim("平台", "120.50");
        assertEquals(0, claimService.receivable().subtract(base).compareTo(new BigDecimal("620.50")),
                "申请中 500+120.50 全部计入索赔应收");

        // c1 到账 → 退出应收
        claimVoucher(c1);
        ClaimDtos.ReceiveReq receive = new ClaimDtos.ReceiveReq();
        receive.setAccountId(account("微信", "0"));
        claimService.receive(c1, receive, OP, OPERATOR);
        assertEquals(0, claimService.receivable().subtract(base).compareTo(new BigDecimal("120.50")));

        // c2 放弃 → 退出应收
        ClaimDtos.AbandonReq abandon = new ClaimDtos.AbandonReq();
        abandon.setRemark("平台拒赔,金额太小不追了");
        claimService.abandon(c2, abandon, OP, OPERATOR);
        assertEquals(0, claimService.receivable().subtract(base).compareTo(BigDecimal.ZERO));
    }

    // ============================== ② 索赔:到账落流水 ==============================

    @Test
    @DisplayName("索赔到账:写 cash_flow(索赔到账→其他收入-赔付)+ 回填 cash_flow_id + 账户余额增加;重复登记被拒")
    void receiveWritesClaimIncomeFlow() {
        Long accountId = account("银行卡", "100");
        Long claimId = claim("厂家", "88.80");
        claimVoucher(claimId);

        ClaimDtos.ReceiveReq req = new ClaimDtos.ReceiveReq();
        req.setAccountId(accountId);
        req.setReceivedAmount(new BigDecimal("80.00")); // 厂家打折赔
        ClaimDtos.ClaimRow row = claimService.receive(claimId, req, OP, OPERATOR);

        assertEquals(Claim.STATUS_RECEIVED, row.getClaimStatus());
        assertNotNull(row.getCashFlowId(), "到账必须回填 cash_flow_id");
        CashFlow flow = cashFlowMapper.selectById(row.getCashFlowId());
        assertEquals("索赔到账", flow.getCategory());
        assertEquals(PlLine.OTHER_INCOME_CLAIM.getLabel(), flow.getPlLine(), "赔付进利润表 其他收入-赔付 行(P0-6)");
        assertEquals(0, flow.getAmount().compareTo(new BigDecimal("80.00")));
        assertEquals(ClaimService.REF_DOC_TYPE, flow.getRefDocType());
        assertEquals(claimId, flow.getRefDocId());
        assertEquals(0, accountService.balanceOf(accountId).compareTo(new BigDecimal("180.00")),
                "余额 = 期初100 + 到账80");

        // 已到账不能再登记/放弃(状态机守卫)
        assertThrows(BizException.class, () -> claimService.receive(claimId, req, OP, OPERATOR));
        ClaimDtos.AbandonReq abandon = new ClaimDtos.AbandonReq();
        abandon.setRemark("x");
        assertThrows(BizException.class, () -> claimService.abandon(claimId, abandon, OP, OPERATOR));
    }

    // ============================== ③ 索赔:凭证门禁 ==============================

    @Test
    @DisplayName("凭证硬门禁:无赔付凭证不能登记到账;传凭证后放行")
    void receiveRequiresVoucher() {
        Long accountId = account("微信", "0");
        Long claimId = claim("平台", "45.00");
        ClaimDtos.ReceiveReq req = new ClaimDtos.ReceiveReq();
        req.setAccountId(accountId);
        BizException e = assertThrows(BizException.class,
                () -> claimService.receive(claimId, req, OP, OPERATOR));
        assertTrue(e.getMessage().contains("凭证"), e.getMessage());

        claimVoucher(claimId);
        ClaimDtos.ClaimRow row = claimService.receive(claimId, req, OP, OPERATOR);
        assertEquals(0, row.getReceivedAmount().compareTo(new BigDecimal("45.00")), "缺省到账额=索赔金额");
    }

    // ============================== ④ 索赔:放弃备注必填 ==============================

    @Test
    @DisplayName("放弃索赔:备注必填(空/空白拒绝),放弃后留痕且退出应收")
    void abandonRequiresRemark() {
        Long claimId = claim("厂家", "66.00");
        ClaimDtos.AbandonReq blank = new ClaimDtos.AbandonReq();
        blank.setRemark("   ");
        assertThrows(BizException.class, () -> claimService.abandon(claimId, blank, OP, OPERATOR));

        ClaimDtos.AbandonReq req = new ClaimDtos.AbandonReq();
        req.setRemark("厂家只认摄像头证据,拿不到,放弃");
        claimService.abandon(claimId, req, OP, OPERATOR);
        Claim after = claimMapper.selectById(claimId);
        assertEquals(Claim.STATUS_ABANDONED, after.getClaimStatus());
        assertEquals("厂家只认摄像头证据,拿不到,放弃", after.getRemark());
    }

    // ============================== ⑤ 净损耗 = 损耗 − 已获赔 ==============================

    @Test
    @DisplayName("净损耗=损耗−已获赔(§13.1):盘亏 10.5 已获赔 8 → 净损耗 2.5")
    void netShrinkageIsLossMinusCompensated() {
        BigDecimal baseLoss = claimService.netShrinkage(null, null).getLossAmount();
        BigDecimal baseComp = claimService.netShrinkage(null, null).getCompensatedAmount();

        Product p = product("被吞的红牛M34");
        stockWarehouse(p.getId(), "10"); // 10 × 3.5
        confirmedDoc(DocType.LOSS_OUT, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{p.getId(), "3", "3.5"}); // 损耗 10.5

        Long claimId = claim("厂家", "10.50");
        claimVoucher(claimId);
        ClaimDtos.ReceiveReq receive = new ClaimDtos.ReceiveReq();
        receive.setAccountId(account("微信", "0"));
        receive.setReceivedAmount(new BigDecimal("8.00"));
        claimService.receive(claimId, receive, OP, OPERATOR);

        ClaimDtos.NetShrinkageResp resp = claimService.netShrinkage(null, null);
        assertEquals(0, resp.getLossAmount().subtract(baseLoss).compareTo(new BigDecimal("10.50")),
                "损耗侧=已确认盘亏出库单成本额");
        assertEquals(0, resp.getCompensatedAmount().subtract(baseComp).compareTo(new BigDecimal("8.00")));
        assertEquals(0, resp.getNetAmount().subtract(baseLoss).add(baseComp).compareTo(new BigDecimal("2.50")),
                "净损耗 = 10.5 − 8 = 2.5");
    }

    // ============================== ⑥ 支出单:凭证门禁 + 落流水 ==============================

    @Test
    @DisplayName("支出单:无凭证不能确认;传凭证确认后落流水(电费→杂费行)、余额扣减、状态已完成;重复确认被拒")
    void expenseConfirmWritesFlow() {
        Long accountId = account("现金", "100");
        ExpenseDtos.ExpenseCreateReq req = new ExpenseDtos.ExpenseCreateReq();
        req.setCategory(Expense.CATEGORY_UTILITY);
        req.setAmount(new BigDecimal("20.00"));
        req.setAccountId(accountId);
        req.setBizDate(LocalDate.now());
        req.setRemark("7月电费");
        Long expId = expenseService.create(req, OP, OPERATOR);

        BizException e = assertThrows(BizException.class,
                () -> expenseService.confirm(expId, OP, OPERATOR));
        assertTrue(e.getMessage().contains("凭证"), e.getMessage());

        expenseVoucher(expId);
        ExpenseDtos.ExpenseRow row = expenseService.confirm(expId, OP, OPERATOR);
        assertEquals(Expense.STATUS_DONE, row.getExpStatus());
        CashFlow flow = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, ExpenseService.REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, expId));
        assertEquals("电费", flow.getCategory());
        assertEquals(PlLine.MISC_EXPENSE.getLabel(), flow.getPlLine(), "电费进利润表杂费行");
        assertEquals(CashFlow.DIR_OUT, flow.getDirection());
        assertEquals(0, accountService.balanceOf(accountId).compareTo(new BigDecimal("80.00")));

        assertThrows(BizException.class, () -> expenseService.confirm(expId, OP, OPERATOR));
    }

    // ============================== ⑦ 支出单:买设备进台账 ==============================

    @Test
    @DisplayName("类别=设备购置:设备名必填;确认后同步建台账行(购入价/日期/折余=购入价)并回填 equipment_id;台账可编辑")
    void equipmentExpenseCreatesLedgerRow() {
        Long accountId = account("银行卡", "5000");

        // 设备购置缺设备名 → 拒绝
        ExpenseDtos.ExpenseCreateReq noName = new ExpenseDtos.ExpenseCreateReq();
        noName.setCategory(Expense.CATEGORY_EQUIPMENT);
        noName.setAmount(new BigDecimal("1200"));
        noName.setAccountId(accountId);
        assertThrows(BizException.class, () -> expenseService.create(noName, OP, OPERATOR));

        ExpenseDtos.ExpenseCreateReq req = new ExpenseDtos.ExpenseCreateReq();
        req.setCategory(Expense.CATEGORY_EQUIPMENT);
        req.setAmount(new BigDecimal("1200.00"));
        req.setAccountId(accountId);
        req.setBizDate(LocalDate.of(2026, 8, 1));
        req.setEquipName("立式冰柜");
        Long expId = expenseService.create(req, OP, OPERATOR);
        expenseVoucher(expId);
        ExpenseDtos.ExpenseRow row = expenseService.confirm(expId, OP, OPERATOR);

        assertTrue(row.getIsEquipment());
        assertNotNull(row.getEquipmentId(), "确认后必须回填 equipment_id");
        Equipment equipment = equipmentMapper.selectById(row.getEquipmentId());
        assertEquals("立式冰柜", equipment.getEquipName());
        assertEquals(0, equipment.getBuyPrice().compareTo(new BigDecimal("1200.00")));
        assertEquals(LocalDate.of(2026, 8, 1), equipment.getBuyDate());
        assertEquals(0, equipment.getResidualValue().compareTo(new BigDecimal("1200.00")), "折余默认=购入价(展示用)");
        assertEquals(Equipment.STATUS_IN_USE, equipment.getEquipStatus());
        assertEquals(expId, equipment.getExpenseId());
        // 支出现金进流水(杂费行),台账本身不再进流水:该支出单只有 1 条流水
        assertEquals(1, cashFlowMapper.selectCount(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, ExpenseService.REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, expId)).intValue());

        // 台账编辑(报废)
        ExpenseDtos.EquipmentUpdateReq update = new ExpenseDtos.EquipmentUpdateReq();
        update.setEquipName("立式冰柜(旧)");
        update.setEquipStatus(Equipment.STATUS_SCRAPPED);
        update.setResidualValue(BigDecimal.ZERO);
        expenseService.updateEquipment(equipment.getId(), update, OP, OPERATOR);
        Equipment after = equipmentMapper.selectById(equipment.getId());
        assertEquals(Equipment.STATUS_SCRAPPED, after.getEquipStatus());
        assertEquals(0, after.getResidualValue().compareTo(BigDecimal.ZERO));
    }

    // ============================== ⑧ 线下复合单:三件套原子生成 ==============================

    @Test
    @DisplayName("线下复合单:一次录入同事务生成 sale_record(线下补录/OFFLINE-/豁免标记/不入待结算)+ cash_flow(其他收入-平台外);账户非法整体回滚")
    void offlineCompositeAtomic() {
        Machine m = machine("故障机M34");
        Product p = product("线下卖的和其正M34");
        Long accountId = account("微信", "0");

        ExpenseDtos.OfflineSaleReq req = new ExpenseDtos.OfflineSaleReq();
        req.setMachineId(m.getId());
        req.setProductId(p.getId());
        req.setQty(new BigDecimal("4"));
        req.setAmount(new BigDecimal("13.20"));
        req.setAccountId(accountId);
        req.setBizTime(LocalDateTime.of(2026, 6, 10, 11, 0));
        ExpenseDtos.OfflineSaleResp resp = offlineSaleService.create(req, OP, OPERATOR);

        // ① sale_record
        SaleRecord sale = saleRecordMapper.selectById(resp.getSaleRecordId());
        assertEquals(OfflineSaleService.ORDER_TYPE_OFFLINE, sale.getOrderType());
        assertTrue(sale.getOrderNo().startsWith(OfflineSaleService.ORDER_NO_PREFIX), sale.getOrderNo());
        assertTrue(Boolean.TRUE.equals(sale.getOfflineFlag()), "豁免标记 offline_flag 必须为 true(P2-13)");
        assertNull(sale.getSettlementId(), "线下补录不入待结算");
        // ② cash_flow
        CashFlow flow = cashFlowMapper.selectById(resp.getCashFlowId());
        assertEquals("线下收入", flow.getCategory());
        assertEquals(PlLine.OTHER_INCOME_OFFLINE.getLabel(), flow.getPlLine(), "13.2 进 其他收入-平台外");
        assertEquals(0, flow.getAmount().compareTo(new BigDecimal("13.20")));
        assertEquals(0, accountService.balanceOf(accountId).compareTo(new BigDecimal("13.20")));

        // 原子性:账户不存在 → 写手拒绝(同事务同步事件),整个事务被标记 rollback-only
        // (测试外层事务与服务事务同一物理事务,无法直接观察内层回滚,断言回滚标记即三件套同生共死)
        ExpenseDtos.OfflineSaleReq bad = new ExpenseDtos.OfflineSaleReq();
        bad.setMachineId(m.getId());
        bad.setProductId(p.getId());
        bad.setQty(BigDecimal.ONE);
        bad.setAmount(new BigDecimal("3.30"));
        bad.setAccountId(999999L);
        assertThrows(BizException.class, () -> offlineSaleService.create(bad, OP, OPERATOR));
        assertTrue(TestTransaction.isFlaggedForRollback(),
                "过账失败必须标记整体回滚:sale_record 与 cash_flow 同生共死,不出现半截单");
    }

    // ============================== ⑨ 线下口径:不入销售额/不扣机器账 ==============================

    @Test
    @DisplayName("线下口径守卫:复合单不入销售额(待结算)口径,不扣机器账(机器账后台权威,差异留盘点豁免)")
    void offlineExcludedFromSettlementAndMachineStock() {
        Machine m = machine("故障机2号M34");
        Product p = product("线下卖的可乐M34");
        LocalDate day = LocalDate.of(2026, 6, 10);
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                day.minusDays(1), false, day.minusDays(1).atTime(8, 0), new Object[]{p.getId(), "10", "2.0"});
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                day, false, day.atTime(9, 0), new Object[]{p.getId(), "8", "2.0"});
        insertSale(m.getId(), p.getId(), "1", "正常", day.atTime(10, 0)); // 正常 1 件 ¥1

        ExpenseDtos.OfflineSaleReq req = new ExpenseDtos.OfflineSaleReq();
        req.setMachineId(m.getId());
        req.setProductId(p.getId());
        req.setQty(new BigDecimal("4"));
        req.setAmount(new BigDecimal("13.20"));
        req.setAccountId(account("微信", "0"));
        req.setBizTime(day.atTime(11, 0));
        offlineSaleService.create(req, OP, OPERATOR);

        // 销售额口径 = 仅正常单(线下 13.2 走 其他收入-平台外,不污染待结算)
        CostEngine.MonthAgg agg = costEngine.replay().getSkuMonth()
                .get(CostEngine.Replay.key(p.getId(), "2026-06"));
        assertEquals(0, agg.getSalesAmt().compareTo(BigDecimal.ONE),
                "销售额=正常1:线下13.2不入销售额/待结算口径");
        // 机器账不扣(后台没记录这 4 瓶出货):8 − 正常1 = 7
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId())
                .compareTo(new BigDecimal("7")), "线下 4 瓶不扣机器账,差异由盘点豁免承接");
    }

    // ============================== ⑩ 盘亏行发起索赔:claim_id 回填 ==============================

    @Test
    @DisplayName("从盘亏行发起索赔:归因=吞货/被盗可索赔并回填 claim_id;重复索赔/不可索赔归因被拒")
    void claimFromStocktakeItems() {
        // 直造一张已完成盘点 + 差异行(不走 StocktakeService,避免与 M3-5 并行票互扰)
        Stocktake st = new Stocktake();
        st.setStNo("PD-M34-" + SEQ.incrementAndGet());
        st.setScopeType(Stocktake.SCOPE_WAREHOUSE);
        st.setSnapshotTime(LocalDateTime.now());
        st.setStStatus(Stocktake.ST_DONE);
        stocktakeMapper.insert(st);

        StocktakeItem swallow = item(st.getId(), 101L, "-3", "-10.50", StocktakeItem.REASON_SWALLOW);
        StocktakeItem theft = item(st.getId(), 102L, "-1", "-3.50", StocktakeItem.REASON_THEFT);
        StocktakeItem expire = item(st.getId(), 103L, "-2", "-7.00", StocktakeItem.REASON_EXPIRE);

        // 吞货+被盗两行一张索赔单,金额=盘亏成本额合计 14.00
        ClaimDtos.CreateReq req = new ClaimDtos.CreateReq();
        req.setClaimTarget("厂家");
        req.setAmount(new BigDecimal("14.00"));
        req.setSourceId(st.getId());
        req.setStocktakeItemIds(Arrays.asList(swallow.getId(), theft.getId()));
        Long claimId = claimService.create(req, OP, OPERATOR);

        Claim claim = claimMapper.selectById(claimId);
        assertEquals(Claim.SOURCE_STOCKTAKE, claim.getSourceType());
        assertEquals(st.getId(), claim.getSourceId());
        assertEquals(claimId, stocktakeItemMapper.selectById(swallow.getId()).getClaimId(),
                "盘亏行必须回填 claim_id(吞货可索赔→关联索赔单)");
        assertEquals(claimId, stocktakeItemMapper.selectById(theft.getId()).getClaimId());

        // 同一行重复索赔 → 拒绝(1 行盘亏只能索赔一次)
        ClaimDtos.CreateReq dup = new ClaimDtos.CreateReq();
        dup.setClaimTarget("厂家");
        dup.setAmount(new BigDecimal("10.50"));
        dup.setSourceId(st.getId());
        dup.setStocktakeItemIds(Arrays.asList(swallow.getId()));
        BizException e1 = assertThrows(BizException.class, () -> claimService.create(dup, OP, OPERATOR));
        assertTrue(e1.getMessage().contains("重复"), e1.getMessage());

        // 过期报损归因不可索赔
        ClaimDtos.CreateReq wrongReason = new ClaimDtos.CreateReq();
        wrongReason.setClaimTarget("厂家");
        wrongReason.setAmount(new BigDecimal("7.00"));
        wrongReason.setSourceId(st.getId());
        wrongReason.setStocktakeItemIds(Arrays.asList(expire.getId()));
        BizException e2 = assertThrows(BizException.class, () -> claimService.create(wrongReason, OP, OPERATOR));
        assertTrue(e2.getMessage().contains("不可索赔"), e2.getMessage());
    }

    // ============================== ⑪ M3-9 七律修复:支出单逆向出口 ==============================

    @Test
    @DisplayName("M3-9七律修复:支出单作废(仅待确认,备注强制);已完成红冲=负额红冲行+反向流水回账户+设备台账标退回;双红冲被拒")
    void expenseVoidAndRedFlush() {
        Long accountId = account("现金", "2000");

        // —— 作废:仅待确认,备注强制,作废后不能确认 ——
        ExpenseDtos.ExpenseCreateReq pendingReq = new ExpenseDtos.ExpenseCreateReq();
        pendingReq.setCategory(Expense.CATEGORY_MISC);
        pendingReq.setAmount(new BigDecimal("30.00"));
        pendingReq.setAccountId(accountId);
        Long pendingId = expenseService.create(pendingReq, OP, OPERATOR);
        BizException e0 = assertThrows(BizException.class,
                () -> expenseService.voidExpense(pendingId, "", OP, OPERATOR));
        assertTrue(e0.getMessage().contains("备注"), e0.getMessage());
        expenseService.voidExpense(pendingId, "录错金额,重录", OP, OPERATOR);
        assertEquals(Expense.STATUS_VOID, expenseMapper.selectById(pendingId).getExpStatus());
        expenseVoucher(pendingId);
        assertThrows(BizException.class, () -> expenseService.confirm(pendingId, OP, OPERATOR));

        // —— 红冲:设备购置已确认 → 负额红冲行 + 反向流水 + 台账标退回 ——
        ExpenseDtos.ExpenseCreateReq eqReq = new ExpenseDtos.ExpenseCreateReq();
        eqReq.setCategory(Expense.CATEGORY_EQUIPMENT);
        eqReq.setAmount(new BigDecimal("1200.00"));
        eqReq.setAccountId(accountId);
        eqReq.setEquipName("红冲冰柜");
        Long expId = expenseService.create(eqReq, OP, OPERATOR);
        // 待确认不能红冲(指路作废)
        BizException e1 = assertThrows(BizException.class,
                () -> expenseService.redFlush(expId, "冲一下", OP, OPERATOR));
        assertTrue(e1.getMessage().contains("作废"), e1.getMessage());
        expenseVoucher(expId);
        ExpenseDtos.ExpenseRow confirmed = expenseService.confirm(expId, OP, OPERATOR);
        assertEquals(0, accountService.balanceOf(accountId).compareTo(new BigDecimal("800.00")), "2000−1200");

        Long redId = expenseService.redFlush(expId, "设备退货了,厂家原路退款", OP, OPERATOR);

        assertEquals(Expense.STATUS_RED_FLUSHED, expenseMapper.selectById(expId).getExpStatus(), "原单已红冲(不删)");
        Expense red = expenseMapper.selectById(redId);
        assertEquals(Expense.STATUS_RED, red.getExpStatus());
        assertEquals(expId, red.getRedFlushOf(), "红冲行回链原单");
        assertEquals(0, red.getAmount().compareTo(new BigDecimal("-1200.00")), "负额红冲行");
        assertTrue(red.getExpNo().startsWith("ZCR-"), red.getExpNo());

        // 反向流水:同类别「收」回账户 → 余额恢复;pl_line 同杂费行净额回落
        CashFlow reverse = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, ExpenseService.REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, redId));
        assertNotNull(reverse, "红冲落反向流水(钱只能被单据改)");
        assertEquals(CashFlow.DIR_IN, reverse.getDirection());
        assertEquals("设备购置", reverse.getCategory());
        assertEquals(PlLine.MISC_EXPENSE.getLabel(), reverse.getPlLine(), "杂费行净额自动回落");
        assertEquals(0, accountService.balanceOf(accountId).compareTo(new BigDecimal("2000.00")), "余额恢复");

        // 设备台账行标记退回(不删,留痕)
        Equipment equipment = equipmentMapper.selectById(confirmed.getEquipmentId());
        assertEquals(Equipment.STATUS_RETURNED, equipment.getEquipStatus(), "台账行标[退回]");

        // 一张单只能红冲一次;红冲行自身不能再红冲
        BizException e2 = assertThrows(BizException.class,
                () -> expenseService.redFlush(expId, "再冲", OP, OPERATOR));
        assertTrue(e2.getMessage().contains("仅[已完成]") || e2.getMessage().contains("红冲过"), e2.getMessage());
        BizException e3 = assertThrows(BizException.class,
                () -> expenseService.redFlush(redId, "冲红冲行", OP, OPERATOR));
        assertTrue(e3.getMessage().contains("红冲行"), e3.getMessage());
    }

    // ============================== ⑫ M3-9 七律修复:线下复合单一键冲销 ==============================

    @Test
    @DisplayName("M3-9七律修复:线下复合单一键冲销——三件套整体反向(负量负额红冲行+反向流水+余额退回);老板守卫+备注强制+防双冲销")
    void offlineSaleReverse() {
        Machine m = machine("冲销故障机M39");
        Product p = product("冲销和其正M39");
        Long accountId = account("微信", "0");
        ExpenseDtos.OfflineSaleReq req = new ExpenseDtos.OfflineSaleReq();
        req.setMachineId(m.getId());
        req.setProductId(p.getId());
        req.setQty(new BigDecimal("4"));
        req.setAmount(new BigDecimal("13.20"));
        req.setAccountId(accountId);
        ExpenseDtos.OfflineSaleResp created = offlineSaleService.create(req, OP, OPERATOR);
        assertEquals(0, accountService.balanceOf(accountId).compareTo(new BigDecimal("13.20")));

        // 老板守卫 + 备注强制
        BizException e0 = assertThrows(BizException.class,
                () -> offlineSaleService.reverse(created.getSaleRecordId(), "录错了", "录单员", OP, OPERATOR));
        assertTrue(e0.getMessage().contains("限老板角色"), e0.getMessage());
        BizException e1 = assertThrows(BizException.class,
                () -> offlineSaleService.reverse(created.getSaleRecordId(), " ", "老板", OP, OPERATOR));
        assertTrue(e1.getMessage().contains("备注"), e1.getMessage());

        ExpenseDtos.OfflineSaleResp reversed = offlineSaleService.reverse(
                created.getSaleRecordId(), "顾客其实没转账,录错了", "老板", OP, OPERATOR);

        // ① 冲销行:负量负额,OFFLINE-RF-<原ID>,仍线下补录+豁免标记,不入待结算
        SaleRecord rev = saleRecordMapper.selectById(reversed.getSaleRecordId());
        assertEquals(OfflineSaleService.REVERSE_PREFIX + created.getSaleRecordId(), rev.getOrderNo());
        assertEquals(0, rev.getQty().compareTo(new BigDecimal("-4")));
        assertEquals(0, rev.getAmountReceived().compareTo(new BigDecimal("-13.20")));
        assertEquals(OfflineSaleService.ORDER_TYPE_OFFLINE, rev.getOrderType());
        assertTrue(Boolean.TRUE.equals(rev.getOfflineFlag()));
        assertNull(rev.getSettlementId());
        // ② 反向流水:线下收入「支」→ 余额退回 0
        CashFlow flow = cashFlowMapper.selectById(reversed.getCashFlowId());
        assertEquals(CashFlow.DIR_OUT, flow.getDirection());
        assertEquals("线下收入", flow.getCategory());
        assertEquals(0, flow.getAmount().compareTo(new BigDecimal("13.20")));
        assertEquals(0, accountService.balanceOf(accountId).compareTo(BigDecimal.ZERO), "钱按原路退回");
        // ③ 原单不改写(仍在,历史留痕);列表标注 reversed
        assertNotNull(saleRecordMapper.selectById(created.getSaleRecordId()));
        ExpenseDtos.OfflineSaleRow originRow = offlineSaleService.listRecent(50).stream()
                .filter(r -> r.getSaleRecordId().equals(created.getSaleRecordId()))
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(Boolean.TRUE.equals(originRow.getReversed()), "列表标注原单已被冲销");

        // 防双冲销 + 冲销行不能再冲销
        BizException e2 = assertThrows(BizException.class,
                () -> offlineSaleService.reverse(created.getSaleRecordId(), "再冲一次", "老板", OP, OPERATOR));
        assertTrue(e2.getMessage().contains("冲销过"), e2.getMessage());
        BizException e3 = assertThrows(BizException.class,
                () -> offlineSaleService.reverse(reversed.getSaleRecordId(), "冲冲销行", "老板", OP, OPERATOR));
        assertTrue(e3.getMessage().contains("冲销行"), e3.getMessage());
    }

    private StocktakeItem item(Long stId, Long productId, String diff, String diffAmount, String reason) {
        StocktakeItem item = new StocktakeItem();
        item.setStocktakeId(stId);
        item.setProductId(productId);
        item.setBookQty(new BigDecimal("10"));
        item.setActualQty(new BigDecimal("10").add(new BigDecimal(diff)));
        item.setDiffQty(new BigDecimal(diff));
        item.setDiffAmount(new BigDecimal(diffAmount));
        item.setDiffReason(reason);
        item.setOfflineExempt(false);
        stocktakeItemMapper.insert(item);
        return item;
    }
}
