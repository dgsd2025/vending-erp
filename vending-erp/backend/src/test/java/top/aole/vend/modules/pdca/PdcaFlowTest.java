package top.aole.vend.modules.pdca;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.pdca.domain.entity.ActionItem;
import top.aole.vend.modules.pdca.dto.PdcaDtos;
import top.aole.vend.modules.pdca.mapper.ActionItemMapper;
import top.aole.vend.modules.pdca.service.ActionItemService;
import top.aole.vend.modules.pdca.service.PdcaBoardService;
import top.aole.vend.modules.pdca.service.PdcaMetricService;
import top.aole.vend.modules.prekit.domain.entity.PrekitTicket;
import top.aole.vend.modules.prekit.mapper.PrekitTicketMapper;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4-3 PDCA 改进循环集成测试(vend_test_pdca 独立库,每例事务回滚)。
 *
 * 覆盖验收清单:
 * ① 到期回查三分支:达标→验证通过 / 未达→未见效升级 / 取不到数→需人工判定(状态不动);
 * ② 指标键取数正确(损耗额按原因 / 带回率月均 / 动销率,造数验证);
 * ③ 盘亏向导一键起草预填(来源=盘点/指标=该原因损耗额/验证日=下次月盘);
 * ④ 六环节看板红绿灯口径(盘点灯按损耗阈值,钱账灯复用 M3 灯,无数据=灰);
 * ⑤ 批量到期回查只捞"到期且进行中",未到期不动。
 */
@ActiveProfiles("test-pdca")
class PdcaFlowTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired
    private ActionItemService actionItemService;
    @Autowired
    private PdcaMetricService metricService;
    @Autowired
    private PdcaBoardService boardService;
    @Autowired
    private ActionItemMapper actionItemMapper;
    @Autowired
    private StocktakeService stocktakeService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private MachineMapper machineMapper;
    @Autowired
    private PrekitTicketMapper prekitTicketMapper;

    // ============================== 造数工具 ==============================

    private Product product(String name) {
        Product p = new Product();
        p.setSkuCode("PD" + SEQ.incrementAndGet());
        p.setProductName(name);
        p.setUnit("件");
        p.setProductStatus("在售");
        productMapper.insert(p);
        return p;
    }

    /** 走真实五步流程造一张"已完成"仓库盘点:book=入库量,actual 少 lossQty 件,原因 reason */
    private Long completedStocktakeWithLoss(Long productId, String bookQty, String actualQty, String reason) {
        stockWarehouse(productId, bookQty); // 采购入库垫账面(单价 3.5)
        StocktakeDtos.CreateReq createReq = new StocktakeDtos.CreateReq();
        createReq.setScopeType("仓库");
        createReq.setSourceTask("手动");
        Long stId = stocktakeService.create(createReq, OP);
        StocktakeDtos.SaveItemsReq saveReq = new StocktakeDtos.SaveItemsReq();
        StocktakeDtos.ItemReq row = new StocktakeDtos.ItemReq();
        row.setProductId(productId);
        row.setActualQty(new BigDecimal(actualQty));
        row.setDiffReason(reason);
        saveReq.setRows(Collections.singletonList(row));
        stocktakeService.saveItems(stId, saveReq, OP);
        stocktakeService.submit(stId, OP);
        stocktakeService.confirm(stId, OP, "老板", false, null);
        return stId;
    }

    private Long item(String scene, String metricKey, String param, String target, String op, LocalDate verifyDate) {
        PdcaDtos.ItemSaveReq req = new PdcaDtos.ItemSaveReq();
        req.setSourceScene(scene);
        req.setProblemDesc("测试问题:" + scene + "/" + metricKey);
        req.setMeasure("测试措施");
        req.setMetricKey(metricKey);
        req.setMetricParam(param);
        req.setTargetValue(target == null ? null : new BigDecimal(target));
        req.setCompareOp(op);
        req.setVerifyDate(verifyDate);
        return actionItemService.create(req, OP, "测试员");
    }

    // ============================== ① 回查三分支 ==============================

    @Test
    @DisplayName("回查分支①:指标达标 → 验证通过(任务关闭),verify_value 落实际值")
    void recheckPass() {
        Product p = product("过期面包A");
        // 损耗 10 件 × ¥3.5 = ¥35;目标 ≤50 → 达标
        completedStocktakeWithLoss(p.getId(), "100", "90", "过期报损");
        Long id = item(ActionItem.SCENE_STOCKTAKE, PdcaMetricService.K_LOSS_AMOUNT, "过期报损",
                "50", "<=", LocalDate.now());
        PdcaDtos.RecheckResp resp = actionItemService.recheck(id, "测试员");
        assertEquals(ActionItem.VR_PASS, resp.getResult());
        assertEquals(ActionItem.ST_PASSED, resp.getItemStatus());
        assertEquals(0, resp.getCurrentValue().compareTo(new BigDecimal("35")));
        ActionItem saved = actionItemMapper.selectById(id);
        assertEquals(ActionItem.ST_PASSED, saved.getItemStatus());
        assertEquals(ActionItem.VR_PASS, saved.getVerifyResult());
        assertNotNull(saved.getVerifiedAt());
    }

    @Test
    @DisplayName("回查分支②:指标未达 → 未见效升级(状态升级,note 带升级提示)")
    void recheckFail() {
        Product p = product("过期面包B");
        // 损耗 ¥35;目标 ≤10 → 未达
        completedStocktakeWithLoss(p.getId(), "100", "90", "过期报损");
        Long id = item(ActionItem.SCENE_STOCKTAKE, PdcaMetricService.K_LOSS_AMOUNT, "过期报损",
                "10", "<=", LocalDate.now());
        PdcaDtos.RecheckResp resp = actionItemService.recheck(id, "测试员");
        assertEquals(ActionItem.VR_FAIL, resp.getResult());
        assertEquals(ActionItem.ST_ESCALATED, resp.getItemStatus());
        assertTrue(resp.getNote().contains("升级"));
        // 未见效升级态允许再回查(老板加招后复验)
        assertDoesNotThrow(() -> actionItemService.recheck(id, "测试员"));
    }

    @Test
    @DisplayName("回查分支③:取不到数 → 需人工判定,状态不动(无键/键未注册/本月没盘点三种)")
    void recheckManual() {
        // 无指标键
        Long noKey = item(ActionItem.SCENE_MONEY, null, null, "10", "<=", LocalDate.now());
        PdcaDtos.RecheckResp r1 = actionItemService.recheck(noKey, "测试员");
        assertEquals(ActionItem.VR_MANUAL, r1.getResult());
        assertEquals(ActionItem.ST_OPEN, r1.getItemStatus());

        // 键未注册
        Long badKey = item(ActionItem.SCENE_MONEY, "bi.some_future_metric", null, "10", "<=", LocalDate.now());
        PdcaDtos.RecheckResp r2 = actionItemService.recheck(badKey, "测试员");
        assertEquals(ActionItem.VR_MANUAL, r2.getResult());
        assertTrue(r2.getNote().contains("未注册"));

        // 键合法但本月无已完成盘点 → 损耗额取不到(防"没盘=0损耗"误通过)
        Long noData = item(ActionItem.SCENE_STOCKTAKE, PdcaMetricService.K_LOSS_AMOUNT, "被盗",
                "10", "<=", LocalDate.now());
        PdcaDtos.RecheckResp r3 = actionItemService.recheck(noData, "测试员");
        assertEquals(ActionItem.VR_MANUAL, r3.getResult());
        assertEquals(ActionItem.ST_OPEN, actionItemMapper.selectById(noData).getItemStatus());
        assertTrue(r3.getNote().contains("盘点"));
    }

    // ============================== ② 指标键取数 ==============================

    @Test
    @DisplayName("指标取数:损耗额按原因过滤(过期报损/吞货掉货各归各,合计=全部)")
    void metricLossByReason() {
        Product p1 = product("损耗品1");
        Product p2 = product("损耗品2");
        completedStocktakeWithLoss(p1.getId(), "100", "90", "过期报损"); // ¥35
        completedStocktakeWithLoss(p2.getId(), "100", "96", "吞货掉货"); // ¥14
        assertEquals(0, metricService.resolve(PdcaMetricService.K_LOSS_AMOUNT, "过期报损")
                .value.compareTo(new BigDecimal("35")));
        assertEquals(0, metricService.resolve(PdcaMetricService.K_LOSS_AMOUNT, "吞货掉货")
                .value.compareTo(new BigDecimal("14")));
        assertEquals(0, metricService.resolve(PdcaMetricService.K_LOSS_AMOUNT, null)
                .value.compareTo(new BigDecimal("49")));
    }

    @Test
    @DisplayName("指标取数:带回率=本月已核销配货单 takeback_rate 月均(10%+20%→15%)")
    void metricTakebackRate() {
        Machine m = new Machine();
        m.setMachineCode("PDM" + SEQ.incrementAndGet());
        m.setMachineName("PDCA测试机");
        m.setDeviceId("PDD" + SEQ.incrementAndGet());
        m.setMachineStatus("在线");
        machineMapper.insert(m);
        // 直接落两张已核销单(核销链路 M2-3 已测,这里只验月均聚合口径)
        for (String rate : new String[]{"0.1000", "0.2000"}) {
            PrekitTicket t = new PrekitTicket();
            t.setTicketNo("PDT" + SEQ.incrementAndGet());
            t.setMachineId(m.getId());
            t.setPlanDate(LocalDate.now());
            t.setTicketStatus(PrekitTicket.STATUS_VERIFIED);
            t.setTakebackRate(new BigDecimal(rate));
            t.setVerifyAt(LocalDateTime.now());
            prekitTicketMapper.insert(t);
        }
        PdcaMetricService.Resolved r = metricService.resolve(PdcaMetricService.K_TAKEBACK_RATE, null);
        assertNotNull(r.value);
        assertEquals(0, r.value.compareTo(new BigDecimal("15.0")));
    }

    @Test
    @DisplayName("指标取数:动销率=本月动销SKU/在售SKU(2 在售 1 动销→50%;正常+兑换口径)")
    void metricSellThrough() {
        Product sold = product("动销品");
        Product idle = product("滞销品");
        insertSale(null, sold.getId(), "3", "正常", LocalDateTime.now());
        insertSale(null, sold.getId(), "1", "测试", LocalDateTime.now()); // 测试单不算动销
        insertSale(null, idle.getId(), "0", "退款", LocalDateTime.now()); // 退款不算动销
        PdcaMetricService.Resolved r = metricService.resolve(PdcaMetricService.K_SELL_THROUGH, null);
        assertNotNull(r.value);
        assertEquals(0, r.value.compareTo(new BigDecimal("50.0")), "2 个在售 SKU 中 1 个动销 → 50%,实际:" + r.value);
    }

    // ============================== ③ 盘亏向导一键起草 ==============================

    @Test
    @DisplayName("盘亏起草:预填 来源=盘点/指标=该原因损耗额/验证日=下月1日/目标=损耗减半(≥¥10);账错类原因不起草")
    void draftFromStocktake() {
        Product p1 = product("吞货品");
        Product p2 = product("录错品");
        stockWarehouse(p1.getId(), "100");
        stockWarehouse(p2.getId(), "100");
        StocktakeDtos.CreateReq createReq = new StocktakeDtos.CreateReq();
        createReq.setScopeType("仓库");
        createReq.setSourceTask("手动");
        Long stId = stocktakeService.create(createReq, OP);
        StocktakeDtos.SaveItemsReq saveReq = new StocktakeDtos.SaveItemsReq();
        StocktakeDtos.ItemReq r1 = new StocktakeDtos.ItemReq();
        r1.setProductId(p1.getId());
        r1.setActualQty(new BigDecimal("90")); // 吞货 10 件 ¥35
        r1.setDiffReason("吞货掉货");
        StocktakeDtos.ItemReq r2 = new StocktakeDtos.ItemReq();
        r2.setProductId(p2.getId());
        r2.setActualQty(new BigDecimal("99")); // 录入错误:账错类,不该起草
        r2.setDiffReason("录入错误");
        saveReq.setRows(java.util.Arrays.asList(r1, r2));
        stocktakeService.saveItems(stId, saveReq, OP);
        stocktakeService.submit(stId, OP);
        stocktakeService.confirm(stId, OP, "老板", false, null);

        PdcaDtos.DraftResp resp = actionItemService.draftFromStocktake(stId);
        assertEquals(1, resp.getDrafts().size(), "只有吞货掉货可起草,录入错误(账错类)不起草");
        PdcaDtos.ItemSaveReq draft = resp.getDrafts().get(0);
        assertEquals(ActionItem.SCENE_STOCKTAKE, draft.getSourceScene());
        assertEquals(PdcaMetricService.K_LOSS_AMOUNT, draft.getMetricKey());
        assertEquals("吞货掉货", draft.getMetricParam());
        assertEquals(ActionItemService.REF_STOCKTAKE, draft.getSourceRefType());
        assertEquals(stId, draft.getSourceRefId());
        assertEquals(LocalDate.now().plusMonths(1).withDayOfMonth(1), draft.getVerifyDate(),
                "验证日=下次月盘(下月 1 日)");
        // ¥35 减半=¥18(四舍五入),≥¥10 线
        assertEquals(0, draft.getTargetValue().compareTo(new BigDecimal("18")));
        assertTrue(draft.getProblemDesc().contains("吞货掉货"));

        // 落库后 existing 能查到、防重复起草有依据
        actionItemService.create(draft, OP, "老板");
        assertEquals(1, actionItemService.draftFromStocktake(stId).getExisting().size());

        // 未完成盘点不许起草
        Long stId2 = stocktakeService.create(createReq, OP);
        assertThrows(BizException.class, () -> actionItemService.draftFromStocktake(stId2));
    }

    // ============================== ④ 看板红绿灯口径 ==============================

    @Test
    @DisplayName("看板口径:六环节齐全;盘点损耗 ¥35 落黄灯(>30 ≤50);无数据环节灰灯;openItems 计数")
    void boardLights() {
        Product p = product("看板损耗品");
        completedStocktakeWithLoss(p.getId(), "100", "90", "过期报损"); // 损耗 ¥35 → 黄
        item(ActionItem.SCENE_STOCKTAKE, PdcaMetricService.K_LOSS_AMOUNT, "过期报损",
                "10", "<=", LocalDate.now());
        PdcaDtos.BoardResp board = boardService.board();
        assertEquals(6, board.getScenes().size());
        List<String> names = board.getScenes().stream()
                .map(PdcaDtos.BoardScene::getScene).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList("补货", "采购", "选品", "定价", "钱账", "盘点"), names);

        PdcaDtos.BoardScene st = board.getScenes().get(5);
        // 损耗额指标本身:¥35 在黄线(30)与红线(50)之间 → 黄灯
        PdcaDtos.BoardIndicator lossInd = st.getIndicators().get(0);
        assertEquals("当月损耗额", lossInd.getLabel());
        assertEquals("amber", lossInd.getLevel(), "损耗 ¥35 → 黄;实际展示:" + lossInd.getDisplay());
        // 环节灯=最差指标:本例只盘 1 行且全差异,账实符合率 0% → 红,故环节整体红(worst 口径)
        assertEquals("red", st.getLight(), "符合率 0% 拉低环节灯(worst 定级)");
        assertEquals(1, st.getOpenItems());
        assertTrue(board.getDueCount() >= 1);

        // 补货环节:无货道/无配货单 → 灰灯(没数据≠健康)
        PdcaDtos.BoardScene rep = board.getScenes().get(0);
        assertEquals("gray", rep.getLight());
        // 钱账环节:无逾期/无差异 → 绿灯(复用 M3 灯口径)
        assertEquals("green", board.getScenes().get(4).getLight());
    }

    @Test
    @DisplayName("看板口径:损耗 ¥70 越红线(>50)→ 盘点红灯;符合率<98 → 黄灯参与定级")
    void boardRedLight() {
        Product p = product("大损耗品");
        completedStocktakeWithLoss(p.getId(), "100", "80", "被盗"); // 损耗 20×3.5=¥70 → 红
        PdcaDtos.BoardResp board = boardService.board();
        assertEquals("red", board.getScenes().get(5).getLight());
    }

    // ============================== ⑤ 批量到期回查 ==============================

    @Test
    @DisplayName("批量到期回查:只捞到期且进行中;未到期不动;三分支计数正确")
    void recheckDueBatch() {
        Product p = product("批量回查品");
        completedStocktakeWithLoss(p.getId(), "100", "90", "过期报损"); // ¥35
        Long pass = item(ActionItem.SCENE_STOCKTAKE, PdcaMetricService.K_LOSS_AMOUNT, "过期报损",
                "50", "<=", LocalDate.now().minusDays(1)); // 到期,¥35≤50 → 通过
        Long fail = item(ActionItem.SCENE_STOCKTAKE, PdcaMetricService.K_LOSS_AMOUNT, "过期报损",
                "10", "<=", LocalDate.now()); // 到期,¥35>10 → 未达
        Long manual = item(ActionItem.SCENE_MONEY, null, null, null, "<=", LocalDate.now()); // 到期,无键 → 人工
        Long notDue = item(ActionItem.SCENE_STOCKTAKE, PdcaMetricService.K_LOSS_AMOUNT, "过期报损",
                "50", "<=", LocalDate.now().plusDays(30)); // 未到期,不动

        PdcaDtos.RecheckBatchResp resp = actionItemService.recheckDue("测试员");
        assertEquals(3, resp.getTotal());
        assertEquals(1, resp.getPassed());
        assertEquals(1, resp.getFailed());
        assertEquals(1, resp.getManual());
        assertEquals(ActionItem.ST_PASSED, actionItemMapper.selectById(pass).getItemStatus());
        assertEquals(ActionItem.ST_ESCALATED, actionItemMapper.selectById(fail).getItemStatus());
        assertEquals(ActionItem.ST_OPEN, actionItemMapper.selectById(manual).getItemStatus());
        assertEquals(ActionItem.ST_OPEN, actionItemMapper.selectById(notDue).getItemStatus());
        assertNull(actionItemMapper.selectById(notDue).getVerifiedAt(), "未到期任务不被触碰");
    }

    // ============================== 附:登记基线 + 终态保护 ==============================

    @Test
    @DisplayName("登记即取基线值;终态(验证通过)不许再回查/编辑;手动关闭必填原因")
    void baselineAndGuards() {
        Product p = product("基线品");
        completedStocktakeWithLoss(p.getId(), "100", "90", "过期报损"); // ¥35
        Long id = item(ActionItem.SCENE_STOCKTAKE, PdcaMetricService.K_LOSS_AMOUNT, "过期报损",
                "50", "<=", LocalDate.now());
        ActionItem saved = actionItemMapper.selectById(id);
        assertEquals(0, saved.getBaselineValue().compareTo(new BigDecimal("35")), "登记时自动取基线");

        actionItemService.recheck(id, "测试员"); // → 验证通过
        assertThrows(BizException.class, () -> actionItemService.recheck(id, "测试员"), "终态不许再回查");
        assertThrows(BizException.class, () -> actionItemService.close(id, "x", OP, "测试员"), "终态不许关闭");

        Long open = item(ActionItem.SCENE_MONEY, null, null, null, "<=", LocalDate.now());
        assertThrows(BizException.class, () -> actionItemService.close(open, " ", OP, "测试员"), "关闭必填原因");
        actionItemService.close(open, "问题已随流程调整消失", OP, "测试员");
        assertEquals(ActionItem.ST_CLOSED, actionItemMapper.selectById(open).getItemStatus());
    }
}
