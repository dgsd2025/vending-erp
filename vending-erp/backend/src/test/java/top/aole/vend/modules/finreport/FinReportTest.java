package top.aole.vend.modules.finreport;

import cn.hutool.core.util.IdUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.claim.domain.entity.Claim;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.service.ClaimService;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.service.AssetSnapshotService;
import top.aole.vend.modules.finreport.service.ProfitReportService;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.CashFlowQueryService;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-6 集成测试(vend_test_finreport 独立库,每例事务回滚):
 * 资产快照五分项/公式恒等/UNSET 门控/归档幂等/锁账不重算/趋势
 * + 简版利润表 pl_line 映射一致性/三口径/上期调整/锁账标记/lock_diff_note。
 */
@ActiveProfiles("test-finreport")
class FinReportTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private AssetSnapshotService assetSnapshotService;
    @Autowired
    private ProfitReportService profitReportService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private SettleModeService settleModeService;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private CashFlowQueryService cashFlowQueryService;
    @Autowired
    private PeriodLockService periodLockService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private JdbcTemplate jdbc;

    // ============================== 造数工具 ==============================

    private String month(int minus) {
        return YearMonth.now().minusMonths(minus).format(PERIOD_FMT);
    }

    private Product product(String name) {
        Product p = new Product();
        p.setSkuCode("FR" + SEQ.incrementAndGet());
        p.setProductName(name + "-" + SEQ.get());
        p.setUnit("瓶");
        p.setProductStatus("在售");
        productMapper.insert(p);
        return p;
    }

    private Long account(String name, String opening) {
        MoneyDtos.AccountCreateReq req = new MoneyDtos.AccountCreateReq();
        req.setAccountName(name + "-" + SEQ.incrementAndGet());
        req.setAccountType("微信");
        if (opening != null) {
            req.setOpeningBalance(new BigDecimal(opening));
        }
        return accountService.create(req, OP, "M36测试员");
    }

    private Supplier supplier(String name, String openingPayable) {
        Supplier s = new Supplier();
        s.setSupplierCode("GYS-FR" + SEQ.incrementAndGet());
        s.setSupplierName(name + "-" + SEQ.get());
        s.setOpeningPayable(new BigDecimal(openingPayable));
        s.setCoopStatus("合作中");
        supplierMapper.insert(s);
        return s;
    }

    /** 全字段可控的销售行(利润表/待结算口径测试用) */
    private Long sale(Long productId, String qty, String amount, String orderType,
                      LocalDateTime bizTime, String bookPeriod, Long settlementId) {
        SaleRecord s = new SaleRecord();
        s.setOrderNo("FR-" + IdUtil.getSnowflakeNextIdStr());
        s.setProductId(productId);
        s.setQty(new BigDecimal(qty));
        s.setAmountReceived(new BigDecimal(amount));
        s.setOrderType(orderType);
        s.setBizTime(bizTime);
        s.setBizPeriod(bizTime.format(PERIOD_FMT));
        s.setBookPeriod(bookPeriod != null ? bookPeriod : s.getBizPeriod());
        s.setSettlementId(settlementId);
        saleRecordMapper.insert(s);
        return s.getId();
    }

    private Long claimPending(String amount) {
        ClaimDtos.CreateReq req = new ClaimDtos.CreateReq();
        req.setClaimTarget(Claim.TARGET_FACTORY);
        req.setAmount(new BigDecimal(amount));
        return claimService.create(req, OP, "M36测试员");
    }

    private void postFlow(Long accountId, String direction, String amount, CashFlowCategory category) {
        MoneyPostingEvent event = new MoneyPostingEvent("支出单", SEQ.incrementAndGet(), OP);
        if ("收".equals(direction)) {
            event.inflow(accountId, new BigDecimal(amount), category, LocalDateTime.now(), "M36造数");
        } else {
            event.outflow(accountId, new BigDecimal(amount), category, LocalDateTime.now(), "M36造数");
        }
        publisher.publishEvent(event);
    }

    private BigDecimal row(FinReportDtos.ProfitResp resp, String key) {
        return resp.getRows().stream().filter(r -> key.equals(r.getKey()))
                .findFirst().orElseThrow(() -> new IllegalStateException("缺行:" + key)).getAmount();
    }

    private static void assertMoney(String expect, BigDecimal actual, String msg) {
        assertNotNull(actual, msg);
        assertEquals(0, actual.compareTo(new BigDecimal(expect)),
                msg + ":期望 " + expect + " 实际 " + actual);
    }

    // ============================== 资产快照 ==============================

    @Test
    @DisplayName("① 五分项来源正确 + §13.1 公式恒等:库存+待结算+现金+索赔应收−应付=净家底,各项下钻列表在位")
    void fivePartsAndFormulaIdentity() {
        // 库存:采购 10 × 3.5 = 35
        Product p = product("家底可乐");
        stockWarehouse(p.getId(), "10");
        // 待结算(PLATFORM):正常 10 − 退款 3 = 7;兑换/已结算不参与
        settleModeService.set(SettleModeService.MODE_PLATFORM, OP, "M36测试员");
        sale(p.getId(), "1", "10.00", "正常", LocalDateTime.now(), null, null);
        sale(p.getId(), "1", "-3.00", "退款", LocalDateTime.now(), null, null);
        sale(p.getId(), "1", "5.00", "兑换", LocalDateTime.now(), null, null);
        sale(p.getId(), "1", "8.00", "正常", LocalDateTime.now(), null, 999L);
        // 现金:100 + 50 = 150
        Long acc1 = account("老板微信", "100");
        account("零钱现金", "50");
        // 索赔应收:申请中 500
        claimPending("500");
        // 应付:期初 120
        supplier("陈老板", "120");

        FinReportDtos.AssetSnapshotResp resp = assetSnapshotService.current();
        assertMoney("35.00", resp.getInventoryAmount(), "①库存=10×3.5");
        assertMoney("7.00", resp.getPlatformPending(), "②待结算=正常10−退款3(兑换/已结算不算)");
        assertMoney("150.00", resp.getCashTotal(), "③现金=Σ真实账户余额");
        assertMoney("500.00", resp.getClaimReceivable(), "④索赔应收=申请中");
        assertMoney("120.00", resp.getPayableTotal(), "⑤应付=供应商期初");
        assertMoney("572.00", resp.getNetAsset(), "净家底=35+7+150+500−120(§13.1 恒等)");

        // 下钻来源列表
        assertTrue(resp.getInventoryRows().stream().anyMatch(r -> p.getId().equals(r.getProductId())
                && r.getAmount().compareTo(new BigDecimal("35.00")) == 0), "库存下钻含 SKU 行");
        assertEquals(1, resp.getPendingRows().size(), "待结算下钻按业务月分组");
        assertMoney("7.00", resp.getPendingRows().get(0).getAmount(), "待结算下钻金额");
        assertTrue(resp.getCashRows().stream().anyMatch(r -> r.getAccountId().equals(acc1)
                && r.getBalance().compareTo(new BigDecimal("100.00")) == 0), "现金下钻含账户行");
        assertEquals(1, resp.getClaimRows().size(), "索赔下钻含申请中行");
        assertTrue(resp.getPayableRows().stream()
                .anyMatch(r -> r.getBalance().compareTo(new BigDecimal("120")) == 0), "应付下钻含供应商行");
        assertNull(resp.getSettleBanner(), "PLATFORM 已定型,无待核实横幅");
    }

    @Test
    @DisplayName("② UNSET 门控:结算模式未核实 → 平台待结算恒 0 + 横幅传导到资产快照与利润表")
    void unsetModePendingZeroWithBanner() {
        Product p = product("门控雪碧");
        stockWarehouse(p.getId(), "4");
        sale(p.getId(), "1", "10.00", "正常", LocalDateTime.now(), null, null); // 未结算但 UNSET

        FinReportDtos.AssetSnapshotResp resp = assetSnapshotService.current();
        assertEquals(SettleModeService.MODE_UNSET, resp.getSettleMode());
        assertMoney("0.00", resp.getPlatformPending(), "UNSET 待结算恒 0(不出正式数)");
        assertEquals(SettleModeService.UNSET_BANNER, resp.getSettleBanner(), "横幅原文传导");
        assertTrue(resp.getPendingRows().isEmpty(), "UNSET 不出待结算下钻");
        // 净家底仍恒等(待结算按 0 参与)
        assertMoney("14.00", resp.getNetAsset(), "净家底=库存14+0+0+0−0");

        FinReportDtos.ProfitResp profit = profitReportService.monthly(month(0));
        assertEquals(SettleModeService.UNSET_BANNER, profit.getSettleBanner(), "利润表同样传导横幅");
    }

    @Test
    @DisplayName("③ 月度归档幂等:同月二次归档=重算覆盖同一行(uk 不撞),归档列表仅一行,数值随数据更新")
    void archiveIdempotentRecalc() {
        Product p = product("归档橙汁");
        stockWarehouse(p.getId(), "10"); // 35
        FinReportDtos.SnapshotRow first = assetSnapshotService.archive(month(0), OP, "M36测试员");
        assertMoney("35.00", first.getNetAsset(), "首次归档净家底");

        account("归档后新增账户", "65"); // 家底 +65
        FinReportDtos.SnapshotRow second = assetSnapshotService.archive(month(0), OP, "M36测试员");
        assertEquals(first.getId(), second.getId(), "幂等:同月覆盖同一行,不新增");
        assertMoney("100.00", second.getNetAsset(), "重算后净家底=35+65");
        assertEquals(1, assetSnapshotService.archives().size(), "归档列表仅一行");
    }

    @Test
    @DisplayName("④ 锁账月归档永不重算:锁账线内月份重归档被拒,已归档数值定格")
    void archiveLockedMonthRefused() {
        Product p = product("锁月苏打水");
        stockWarehouse(p.getId(), "10"); // 35
        FinReportDtos.SnapshotRow archived = assetSnapshotService.archive(month(1), OP, "M36测试员");
        assertMoney("35.00", archived.getNetAsset(), "锁前归档成功");

        periodLockService.lock(month(1), OP, "上月报表已出");
        account("锁后新增账户", "999");
        BizException e = assertThrows(BizException.class,
                () -> assetSnapshotService.archive(month(1), OP, "M36测试员"));
        assertTrue(e.getMessage().contains("不重算"), "拒绝提示含永不重算口径:" + e.getMessage());
        assertMoney("35.00", assetSnapshotService.archives().get(0).getNetAsset(), "已归档数值定格");
        assertTrue(assetSnapshotService.archives().get(0).isLocked(), "归档行带锁账标记");
    }

    @Test
    @DisplayName("⑤ 趋势:两月归档 → 旧→新折线数据,净资产逐点正确;即时快照带上月对比")
    void trendFromArchives() {
        Product p = product("趋势矿泉水");
        stockWarehouse(p.getId(), "10"); // 35
        assetSnapshotService.archive(month(1), OP, "M36测试员");
        account("趋势账户", "15"); // +15 → 50
        assetSnapshotService.archive(month(0), OP, "M36测试员");

        List<FinReportDtos.SnapshotRow> trend = assetSnapshotService.trend(12);
        assertEquals(2, trend.size());
        assertEquals(month(1), trend.get(0).getPeriod(), "旧→新排序");
        assertMoney("35.00", trend.get(0).getNetAsset(), "上月点");
        assertMoney("50.00", trend.get(1).getNetAsset(), "本月点");

        FinReportDtos.AssetSnapshotResp now = assetSnapshotService.current();
        assertEquals(month(0), now.getPrevPeriod(), "即时快照带最近归档");
        assertMoney("50.00", now.getPrevNetAsset(), "对比基准=最近归档净资产");
    }

    // ============================== 简版利润表 ==============================

    @Test
    @DisplayName("⑥ 利润表各行取数与 pl_line 一一映射(造全类别流水):行金额=plMonthlySummary 净额,非PL类不进任何行,经营利润恒等")
    void profitRowsMatchPlLineMapping() {
        // 销售侧:采购 10×3.5;正常卖 2 件 9.9 → 毛利 2.9
        Product p = product("映射红牛");
        stockWarehouse(p.getId(), "10");
        sale(p.getId(), "2", "9.90", "正常", LocalDateTime.now(), null, null);
        // 损耗侧:盘亏 1 × 3.5
        confirmedDoc(DocType.LOSS_OUT, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{p.getId(), "1", "3.5"});
        // 流水侧:全类别各造一笔
        Long acc = account("映射账户", "1000");
        postFlow(acc, "支", "5.00", CashFlowCategory.PLATFORM_FEE);
        postFlow(acc, "支", "3.00", CashFlowCategory.UTILITY);
        postFlow(acc, "支", "2.00", CashFlowCategory.REPAIR);
        postFlow(acc, "支", "1.00", CashFlowCategory.MISC);
        postFlow(acc, "支", "4.00", CashFlowCategory.EQUIPMENT);
        postFlow(acc, "支", "2.50", CashFlowCategory.COST_ADJUST_FLOW);
        postFlow(acc, "收", "8.00", CashFlowCategory.CLAIM_INCOME);
        postFlow(acc, "收", "6.00", CashFlowCategory.OFFLINE_INCOME);
        postFlow(acc, "收", "4.00", CashFlowCategory.EXCHANGE_SUBSIDY);
        postFlow(acc, "支", "50.00", CashFlowCategory.SUPPLIER_PAYMENT); // 不进利润表

        FinReportDtos.ProfitResp resp = profitReportService.monthly(month(0));
        assertMoney("9.90", row(resp, "salesIncome"), "销售收入");
        assertMoney("-7.00", row(resp, "salesCost"), "销售成本=2×3.5");
        assertMoney("2.90", row(resp, "grossProfit"), "毛利=实收−加权成本");
        assertMoney("-5.00", row(resp, "platformFee"), "平台手续费行");
        assertMoney("-10.00", row(resp, "miscExpense"), "杂费行=电费3+维修2+杂支1+设备4");
        assertMoney("-3.50", row(resp, "shrinkage"), "损耗行=盘亏成本额");
        assertMoney("-2.50", row(resp, "costAdjust"), "成本调整行");
        assertMoney("8.00", row(resp, "otherIncomeClaim"), "其他收入-赔付");
        assertMoney("6.00", row(resp, "otherIncomeOffline"), "其他收入-平台外");
        assertMoney("4.00", row(resp, "otherIncomeSubsidy"), "其他收入-补贴");
        assertMoney("0.00", row(resp, "priorAdjust"), "无补导 → 上期调整 0");
        assertMoney("-0.10", row(resp, "operatingProfit"),
                "经营利润=2.9−5−10−3.5−2.5+8+6+4(§13.1 恒等)");
        assertMoney("-0.10", resp.getOperatingProfit(), "合计字段同值");
        assertMoney("-50.00", resp.getNonPlNet(), "供应商付款只进本金往来,不进任何利润表行");

        // 一一映射交叉核:流水侧各行 = plMonthlySummary 当月净额
        Map<String, BigDecimal> summary = new java.util.HashMap<>();
        for (MoneyDtos.PlSummaryRow r : cashFlowQueryService.plMonthlySummary(month(0), month(0))) {
            summary.merge(r.getPlLine(), r.getNet(), BigDecimal::add);
        }
        assertEquals(0, summary.get("平台手续费").compareTo(row(resp, "platformFee")), "手续费=取数口净额");
        assertEquals(0, summary.get("杂费").compareTo(row(resp, "miscExpense")), "杂费=取数口净额");
        assertEquals(0, summary.get("成本调整").compareTo(row(resp, "costAdjust")), "成本调整=取数口净额");
        assertEquals(0, summary.get("其他收入-赔付").compareTo(row(resp, "otherIncomeClaim")), "赔付=取数口净额");
    }

    @Test
    @DisplayName("⑦ 三口径守恒:退款负入、兑换收入0但计成本、测试/线下补录不进收入")
    void orderTypeCalibers() {
        Product p = product("口径脉动");
        stockWarehouse(p.getId(), "10"); // 3.5
        sale(p.getId(), "2", "9.90", "正常", LocalDateTime.now(), null, null);   // +9.9 / 成本 7
        sale(p.getId(), "1", "-3.00", "退款", LocalDateTime.now(), null, null);  // −3 / 成本 −3.5 回池
        sale(p.getId(), "1", "5.00", "兑换", LocalDateTime.now(), null, null);   // 收入 0 / 成本 3.5
        sale(p.getId(), "1", "99.00", "测试", LocalDateTime.now(), null, null);  // 整行不计
        sale(p.getId(), "1", "6.00", "线下补录", LocalDateTime.now(), null, null); // 收入 0(线下走复合单另行入账)

        FinReportDtos.ProfitResp resp = profitReportService.monthly(month(0));
        assertMoney("6.90", row(resp, "salesIncome"), "收入=9.9−3(兑换/测试/线下不进)");
        assertMoney("-10.50", row(resp, "salesCost"), "成本=7−3.5+3.5+3.5(线下补录也计出货成本)");
        assertMoney("-3.60", row(resp, "grossProfit"), "毛利=6.9−10.5");
    }

    @Test
    @DisplayName("⑧ 上期调整行(P0-2):锁后补导销售拆出主表 → 上期调整=补导收入−补导成本,与 priorAdjust 取数口吻合")
    void priorAdjustRow() {
        Product p = product("补导和其正");
        // 采购入库业务时间放上月初:补导销售(上月18日)在事件时序上才有成本池可结转
        LocalDate lastMonthStart = YearMonth.now().minusMonths(1).atDay(1);
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                lastMonthStart, false, lastMonthStart.atTime(8, 0), new Object[]{p.getId(), "10", "3.5"});
        periodLockService.lock(month(1), OP, "上月报表已出");
        // 当月正常销售
        sale(p.getId(), "2", "9.90", "正常", LocalDateTime.now(), null, null);
        // 锁后补导:业务=上月,入账=当月(biz≠book)
        LocalDateTime lastMonth = YearMonth.now().minusMonths(1).atDay(18).atTime(12, 0);
        sale(p.getId(), "2", "6.00", "正常", lastMonth, month(0), null);

        FinReportDtos.ProfitResp resp = profitReportService.monthly(month(0));
        assertMoney("9.90", row(resp, "salesIncome"), "主表收入不含补导行");
        assertMoney("-1.00", row(resp, "priorAdjust"), "上期调整=6.00−2×3.5");
        assertTrue(resp.getRows().stream().filter(r -> "priorAdjust".equals(r.getKey()))
                .findFirst().orElseThrow(IllegalStateException::new).getNote().contains("1 笔"), "行注释报补导笔数");

        // 与 M1 落地的 priorAdjust 聚合取数口吻合(销售侧金额)
        Map<String, Object> agg = periodLockService.priorAdjust(month(0));
        assertMoney("6.00", (BigDecimal) agg.get("saleAmount"), "priorAdjust 聚合口=补导实收 6");

        // 上月利润表不被补导污染(旧报表永不重算):book_period=上月 无该行
        FinReportDtos.ProfitResp lastResp = profitReportService.monthly(month(1));
        assertMoney("0.00", row(lastResp, "salesIncome"), "上月入账桶无补导收入");
    }

    @Test
    @DisplayName("⑨ 锁账月利润表:标记已归档不重算(locked+口径说明)")
    void lockedMonthProfitMarked() {
        Product p = product("锁月冰红茶");
        stockWarehouse(p.getId(), "4");
        periodLockService.lock(month(1), OP, "月报已出");

        FinReportDtos.ProfitResp locked = profitReportService.monthly(month(1));
        assertTrue(locked.isLocked(), "锁账月 locked=true");
        assertNotNull(locked.getLockedNote());
        assertTrue(locked.getLockedNote().contains("已归档不重算"), locked.getLockedNote());

        FinReportDtos.ProfitResp current = profitReportService.monthly(month(0));
        assertFalse(current.isLocked(), "当月未锁不标");
        assertNull(current.getLockedNote());
    }

    @Test
    @DisplayName("⑩ lock_diff_note(P0-2):锁后补导命中已核销结算单区间 → 只写提示、状态不变;差异消失自动清提示")
    void settledSettlementLockDiffNote() {
        Product p = product("差异提示可乐");
        stockWarehouse(p.getId(), "10");
        // 已核销平台结算单:覆盖上月整月区间(直造行,不依赖并行票 M3-3 的核销服务)
        LocalDate start = YearMonth.now().minusMonths(1).atDay(1);
        LocalDate end = YearMonth.now().minusMonths(1).atEndOfMonth();
        jdbc.update("INSERT INTO yc_vend_settlement (stmt_no, period_start, period_end, " +
                        "platform_amount, actual_amount, stl_status) VALUES (?,?,?,?,?,'已核销')",
                "STL-M36-" + SEQ.incrementAndGet(), start, end, 100, 98);
        Long stlId = jdbc.queryForObject("SELECT MAX(id) FROM yc_vend_settlement", Long.class);

        periodLockService.lock(month(1), OP, "月报已出");
        // 锁后补导 2 笔命中区间(biz=上月 / book=当月 / 未回填结算单)
        Long saleA = sale(p.getId(), "1", "3.00", "正常", start.atTime(10, 0), month(0), null);
        Long saleB = sale(p.getId(), "2", "6.00", "正常", start.plusDays(2).atTime(11, 0), month(0), null);

        profitReportService.refreshLockDiffNotes();
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT stl_status, lock_diff_note FROM yc_vend_settlement WHERE id=?", stlId);
        String note = String.valueOf(after.get("lock_diff_note"));
        assertTrue(note.contains("锁后新增差异") && note.contains("2 笔") && note.contains("9.00"),
                "提示带笔数与金额:" + note);
        assertEquals("已核销", after.get("stl_status"), "状态不变:只提示不回退(P0-2)");

        // 利润表响应携带提示行
        FinReportDtos.ProfitResp resp = profitReportService.monthly(month(0));
        assertTrue(resp.getLockDiffNotes().stream().anyMatch(r -> r.getSettlementId().equals(stlId)
                && r.getNote().contains("2 笔")), "利润表带 lock_diff_note 提示");

        // 幂等:内容没变不重复 UPDATE
        assertEquals(0, profitReportService.refreshLockDiffNotes(), "无变化不落更新");

        // 差异消失(补导行被后续结算含入)→ 提示自动清
        // 用 mapper 改而非 JdbcTemplate:同事务里 MyBatis 一级缓存只被 mapper 写清空
        for (Long sid : java.util.Arrays.asList(saleA, saleB)) {
            SaleRecord s = saleRecordMapper.selectById(sid);
            s.setSettlementId(stlId);
            saleRecordMapper.updateById(s);
        }
        profitReportService.refreshLockDiffNotes();
        assertNull(jdbc.queryForMap("SELECT lock_diff_note FROM yc_vend_settlement WHERE id=?", stlId)
                .get("lock_diff_note"), "差异消失 → 清提示");
    }
}
