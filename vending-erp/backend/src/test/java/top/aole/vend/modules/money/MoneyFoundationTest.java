package top.aole.vend.modules.money;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.domain.enums.AttachmentType;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.enums.PlLine;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.money.service.CashFlowQueryService;
import top.aole.vend.modules.money.service.SettleModeService;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3-1 钱账基座集成测试(vend_test_money 独立库,每例事务回滚)。
 *
 * 覆盖验收清单:①期初只能设一次 ②余额=期初+Σ流水 ③写手包私有(反射断言)
 * ④pl_line 枚举完备性(每类别唯一去处+全行覆盖) ⑤凭证上传路径安全(../文件名)
 * ⑥凭证关联单据 ⑦settle.mode 读写 ⑧虚拟账户不可手工收支 + 无单据拒绝/聚合口。
 */
@ActiveProfiles("test-money")
class MoneyFoundationTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private AccountService accountService;
    @Autowired
    private CashFlowQueryService cashFlowQueryService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private SettleModeService settleModeService;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private CashFlowMapper cashFlowMapper;

    // ============================== 造数工具 ==============================

    private Long account(String type, String opening) {
        MoneyDtos.AccountCreateReq req = new MoneyDtos.AccountCreateReq();
        req.setAccountName("测试" + type + SEQ.incrementAndGet());
        req.setAccountType(type);
        if (opening != null) {
            req.setOpeningBalance(new BigDecimal(opening));
        }
        return accountService.create(req, OP, "测试员");
    }

    /** 通过事件通道落一笔流水(唯一合法路径) */
    private void post(String refDocType, Long refDocId, Long accountId, String dir,
                      String amount, CashFlowCategory category, LocalDateTime bizTime) {
        MoneyPostingEvent event = new MoneyPostingEvent(refDocType, refDocId, OP);
        if (CashFlow.DIR_IN.equals(dir)) {
            event.inflow(accountId, new BigDecimal(amount), category, bizTime, "测试流水");
        } else {
            event.outflow(accountId, new BigDecimal(amount), category, bizTime, "测试流水");
        }
        publisher.publishEvent(event);
    }

    // ============================== ① 期初只能设一次 ==============================

    @Test
    @DisplayName("期初余额只能设一次:二次设置被拒并指向资金调整单")
    void openingOnlyOnce() {
        Long id = account("微信", "1000.00");
        BizException e = assertThrows(BizException.class,
                () -> accountService.setOpening(id, new BigDecimal("2000"), OP, "测试员"));
        assertTrue(e.getMessage().contains("只能设一次"), e.getMessage());
        assertTrue(e.getMessage().contains("资金调整单"), e.getMessage());

        // 建户没给期初 → 之后可设一次,再设第二次同样被拒
        Long id2 = account("现金", null);
        accountService.setOpening(id2, new BigDecimal("50"), OP, "测试员");
        assertThrows(BizException.class,
                () -> accountService.setOpening(id2, new BigDecimal("60"), OP, "测试员"));
    }

    // ============================== ② 余额 = 期初 + Σ流水 ==============================

    @Test
    @DisplayName("余额=期初+Σ流水(实时推算不落表):收+支混合")
    void balanceIsOpeningPlusFlows() {
        Long id = account("银行卡", "1000.00");
        LocalDateTime now = LocalDateTime.now();
        post("付款单", 901L, id, "支", "300.50", CashFlowCategory.SUPPLIER_PAYMENT, now);
        post("索赔单", 902L, id, "收", "88.80", CashFlowCategory.CLAIM_INCOME, now);
        post("支出单", 903L, id, "支", "12.30", CashFlowCategory.UTILITY, now);
        // 1000 - 300.50 + 88.80 - 12.30 = 776.00
        assertEquals(0, accountService.balanceOf(id).compareTo(new BigDecimal("776.00")));
        // 列表口同一口径
        MoneyDtos.AccountRow row = accountService.list().stream()
                .filter(r -> r.getId().equals(id)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, row.getBalance().compareTo(new BigDecimal("776.00")));
        assertTrue(row.getOpeningLocked());
    }

    // ============================== ③ 写手包私有(反射断言) ==============================

    @Test
    @DisplayName("CashFlowWriter 包私有:类非 public 且无 public 方法(全系统无绕开单据直改钱账的公开入口)")
    void writerIsPackagePrivate() throws Exception {
        Class<?> writer = Class.forName("top.aole.vend.modules.money.service.CashFlowWriter");
        assertFalse(Modifier.isPublic(writer.getModifiers()), "CashFlowWriter 不许是 public 类");
        for (Method m : writer.getDeclaredMethods()) {
            assertFalse(Modifier.isPublic(m.getModifiers()),
                    "CashFlowWriter 不许有 public 方法:" + m.getName());
        }
    }

    // ============================== ④ pl_line 枚举完备性 ==============================

    @Test
    @DisplayName("pl_line 完备性:每类别有且只有一个非空去处;10 个利润表行全部被类别覆盖")
    void plLineMappingComplete() {
        Set<PlLine> covered = new HashSet<>();
        for (CashFlowCategory c : CashFlowCategory.values()) {
            assertNotNull(c.getPlLine(), "类别「" + c.getLabel() + "」没有利润表去处");
            assertNotNull(c.getLabel());
            covered.add(c.getPlLine());
        }
        assertEquals(EnumSet.allOf(PlLine.class), covered, "存在没有任何类别映射到的利润表行");
        // 类别中文值唯一(落库值不许撞)
        Set<String> labels = new HashSet<>();
        for (CashFlowCategory c : CashFlowCategory.values()) {
            assertTrue(labels.add(c.getLabel()), "类别中文值重复:" + c.getLabel());
        }
        // 写手落库的 pl_line 与枚举推导一致(抽查一条)
        Long id = account("支付宝", "0");
        post("支出单", 904L, id, "支", "10", CashFlowCategory.REPAIR, LocalDateTime.now());
        CashFlow flow = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getAccountId, id).last("LIMIT 1"));
        assertEquals(PlLine.MISC_EXPENSE.getLabel(), flow.getPlLine());
        assertEquals(CashFlowCategory.REPAIR.getLabel(), flow.getCategory());
    }

    // ============================== ⑤ 凭证上传路径安全 ==============================

    @Test
    @DisplayName("凭证归档名服务端生成:../穿越文件名被剥净,文件落在存储根内")
    void attachmentPathTraversalSafe() throws Exception {
        byte[] png = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
        Long attId = attachmentService.upload("payment", 905L, "转账截图",
                "../../../../etc/危险覆盖.png", png, OP, "测试员");
        assertNotNull(attId);
        AttachmentService.Preview preview = attachmentService.load(attId);
        File stored = new File(preview.attachment.getFilePath());
        assertTrue(stored.exists());
        // 归档物理名 = 服务端 ATT-uuid.png,不含客户端名的任何成分
        assertTrue(stored.getName().startsWith("ATT-") && stored.getName().endsWith(".png"),
                "归档名必须服务端生成:" + stored.getName());
        // 落在存储根 target/test-attachments 之内(未被 ../ 带出)
        String root = new File("target/test-attachments").getCanonicalPath();
        assertTrue(stored.getCanonicalPath().startsWith(root),
                "文件被写出存储根:" + stored.getCanonicalPath());
        // 展示名剥掉路径只剩净名
        assertEquals("危险覆盖.png", preview.attachment.getFileName());
        // 扩展名白名单:可执行类后缀拒绝
        assertThrows(BizException.class, () -> attachmentService.upload(
                "payment", 905L, "转账截图", "evil.sh", png, OP, "测试员"));
        // 非法关联类型拒绝
        assertThrows(BizException.class, () -> attachmentService.upload(
                "hacker_table", 905L, "转账截图", "a.png", png, OP, "测试员"));
    }

    // ============================== ⑥ 凭证关联单据 ==============================

    @Test
    @DisplayName("凭证关联单据:按 refType+refId 可查回;countOf 为后续票硬门禁取数口")
    void attachmentLinkedToDoc() {
        byte[] bytes = "bill".getBytes(StandardCharsets.UTF_8);
        attachmentService.upload("settlement", 906L, "平台账单", "7月账单.xlsx", bytes, OP, "测试员");
        attachmentService.upload("settlement", 906L, "转账截图", "到账.png", bytes, OP, "测试员");
        List<MoneyDtos.AttachmentRow> rows = attachmentService.listOf("settlement", 906L);
        assertEquals(2, rows.size());
        assertEquals(2, attachmentService.countOf("settlement", 906L));
        assertEquals(0, attachmentService.countOf("settlement", 999906L));
        Set<String> types = rows.stream().map(MoneyDtos.AttachmentRow::getAttType).collect(Collectors.toSet());
        assertTrue(types.contains(AttachmentType.PLATFORM_BILL.getLabel()));
        // refId 缺失拒绝
        assertThrows(BizException.class, () -> attachmentService.upload(
                "settlement", null, "平台账单", "a.png", bytes, OP, "测试员"));
    }

    // ============================== ⑦ settle.mode 读写 ==============================

    @Test
    @DisplayName("settle.mode:默认 UNSET 带待核实横幅;设 PLATFORM 后横幅消失;非法值拒绝")
    void settleModeReadWrite() {
        MoneyDtos.SettleModeResp resp = settleModeService.get();
        assertEquals(SettleModeService.MODE_UNSET, resp.getMode());
        assertNotNull(resp.getBanner(), "UNSET 必须带横幅文案");
        assertTrue(resp.getBanner().contains("待核实"));
        assertEquals(3, resp.getOptions().size());

        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, "测试员", "老板");
        MoneyDtos.SettleModeResp after = settleModeService.get();
        assertEquals(SettleModeService.MODE_PLATFORM, after.getMode());
        assertNull(after.getBanner(), "定型后不再显示横幅");
        assertEquals(SettleModeService.MODE_PLATFORM, settleModeService.currentMode());

        assertThrows(BizException.class, () -> settleModeService.set("WECHAT_PAY", OP, "测试员", "老板"));

        // M3-9 P1-7:结算模式是全系统钱账口径总开关——非老板角色/无角色一律拒绝
        BizException noBoss = assertThrows(BizException.class,
                () -> settleModeService.set(SettleModeService.MODE_DIRECT, OP, "测试员", "员工"));
        assertTrue(noBoss.getMessage().contains("限老板角色"), noBoss.getMessage());
        assertThrows(BizException.class,
                () -> settleModeService.set(SettleModeService.MODE_DIRECT, OP, "测试员", null));
        assertEquals(SettleModeService.MODE_PLATFORM, settleModeService.currentMode(), "被拒后模式不变");
    }

    // ============================== ⑧ 虚拟账户不可手工收支 ==============================

    @Test
    @DisplayName("虚拟账户(平台待结算/老板垫付)不可手工收支:资金调整打到虚拟账户被拒;业务流水放行")
    void virtualAccountRejectsManualAdjust() {
        Long virtualId = account("平台待结算", "0");
        BizException e = assertThrows(BizException.class, () -> post(
                "资金调整单", 907L, virtualId, "收", "100", CashFlowCategory.CASH_ADJUST, LocalDateTime.now()));
        assertTrue(e.getMessage().contains("不可手工收支"), e.getMessage());
        // 业务单据流水(销售收款入虚账)是合法的
        post("平台结算单", 908L, virtualId, "收", "100", CashFlowCategory.SALE_INCOME, LocalDateTime.now());
        assertEquals(0, accountService.balanceOf(virtualId).compareTo(new BigDecimal("100")));
        // 真实账户的资金调整放行(M3-5 的通道)
        Long realId = account("现金", "0");
        post("资金调整单", 909L, realId, "支", "5", CashFlowCategory.CASH_ADJUST, LocalDateTime.now());
        assertEquals(0, accountService.balanceOf(realId).compareTo(new BigDecimal("-5")));
    }

    // ============================== 补充:单据必填 / 停用 / 金额方向 ==============================

    @Test
    @DisplayName("钱只能被单据改:无 refDoc / 金额≤0 / 方向非法 / 停用账户 全部拒绝")
    void writerValidations() {
        Long id = account("微信", "0");
        LocalDateTime now = LocalDateTime.now();
        // 无关联单据
        assertThrows(BizException.class, () -> post(null, 1L, id, "收", "1",
                CashFlowCategory.MISC, now));
        assertThrows(BizException.class, () -> post("支出单", null, id, "收", "1",
                CashFlowCategory.MISC, now));
        // 金额≤0
        assertThrows(BizException.class, () -> post("支出单", 910L, id, "支", "0",
                CashFlowCategory.MISC, now));
        assertThrows(BizException.class, () -> post("支出单", 910L, id, "支", "-3",
                CashFlowCategory.MISC, now));
        // 停用账户拒收流水
        accountService.changeStatus(id, 0, OP, "测试员");
        BizException e = assertThrows(BizException.class, () -> post("支出单", 911L, id, "支", "1",
                CashFlowCategory.MISC, now));
        assertTrue(e.getMessage().contains("已停用"), e.getMessage());
    }

    @Test
    @DisplayName("流水分页筛选(账户/类别/期间)与 pl_line×月聚合口(利润表取数)")
    void flowQueryAndPlSummary() {
        Long a1 = account("微信", "0");
        Long a2 = account("现金", "0");
        LocalDateTime now = LocalDateTime.now();
        String month = YearMonth.now().format(PERIOD);
        post("支出单", 912L, a1, "支", "20", CashFlowCategory.UTILITY, now);
        post("支出单", 913L, a1, "支", "30", CashFlowCategory.REPAIR, now);
        post("索赔单", 914L, a2, "收", "45", CashFlowCategory.CLAIM_INCOME, now);

        // 账户筛选
        Page<MoneyDtos.FlowRow> byAccount = cashFlowQueryService.page(1, 20, a1, null, null, null);
        assertEquals(2, byAccount.getTotal());
        assertTrue(byAccount.getRecords().stream().allMatch(r -> r.getAccountId().equals(a1)));
        assertNotNull(byAccount.getRecords().get(0).getAccountName());
        // 类别筛选
        Page<MoneyDtos.FlowRow> byCategory = cashFlowQueryService.page(1, 20, null, "电费", null, null);
        assertEquals(1, byCategory.getTotal());
        assertEquals("杂费", byCategory.getRecords().get(0).getPlLine());
        // 期间筛选:未来月无数据
        assertEquals(0, cashFlowQueryService.page(1, 20, null, null, "2999-01", "2999-12").getTotal());

        // pl_line×月聚合:电费+维修 → 杂费行合并 50 支;索赔 → 其他收入-赔付 45 收
        List<MoneyDtos.PlSummaryRow> summary = cashFlowQueryService.plMonthlySummary(month, month);
        MoneyDtos.PlSummaryRow misc = summary.stream()
                .filter(r -> "杂费".equals(r.getPlLine())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, misc.getOutflow().compareTo(new BigDecimal("50")));
        assertEquals(0, misc.getNet().compareTo(new BigDecimal("-50")));
        MoneyDtos.PlSummaryRow claim = summary.stream()
                .filter(r -> "其他收入-赔付".equals(r.getPlLine())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, claim.getInflow().compareTo(new BigDecimal("45")));
    }

    // ============================== M3-9 非现金过账纪律 ==============================

    @Test
    @DisplayName("M3-9:非现金过账(account_id=NULL)只许白名单类别(成本调整/结算差异),不动任何账户余额;其他类别无账户被拒")
    void cashlessPostingDiscipline() {
        Long id = account("微信", "100");
        // 白名单:成本调整——落流水但不挂账户
        MoneyPostingEvent ok = new MoneyPostingEvent("成本调整单", 920L, OP);
        ok.pnlOutflow(new BigDecimal("2.5"), CashFlowCategory.COST_ADJUST_FLOW,
                LocalDateTime.now(), "已售部分Δ(非现金)");
        publisher.publishEvent(ok);
        CashFlow flow = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, "成本调整单").eq(CashFlow::getRefDocId, 920L));
        assertNotNull(flow);
        assertNull(flow.getAccountId(), "非现金行不属于任何账户");
        assertEquals(PlLine.COST_ADJUST.getLabel(), flow.getPlLine(), "只进利润表行");
        assertEquals(0, accountService.balanceOf(id).compareTo(new BigDecimal("100")),
                "任何账户余额都不受非现金行影响");
        // 利润表聚合口吃得到非现金行
        String month = YearMonth.now().format(PERIOD);
        MoneyDtos.PlSummaryRow adj = cashFlowQueryService.plMonthlySummary(month, month).stream()
                .filter(r -> PlLine.COST_ADJUST.getLabel().equals(r.getPlLine()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, adj.getNet().compareTo(new BigDecimal("-2.5")));

        // 非白名单类别不挂账户 → 拒绝(纪律:现金类流水必须有真实账户)
        MoneyPostingEvent bad = new MoneyPostingEvent("支出单", 921L, OP);
        bad.pnlOutflow(BigDecimal.ONE, CashFlowCategory.MISC, LocalDateTime.now(), "非法非现金");
        BizException e = assertThrows(BizException.class, () -> publisher.publishEvent(bad));
        assertTrue(e.getMessage().contains("必须挂真实账户"), e.getMessage());
    }
}
