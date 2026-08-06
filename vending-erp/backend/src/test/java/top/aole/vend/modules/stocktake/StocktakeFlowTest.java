package top.aole.vend.modules.stocktake;

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
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.mapper.DocItemMapper;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;
import top.aole.vend.modules.stock.mapper.MachineStockSnapshotMapper;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.mapper.StocktakeItemMapper;
import top.aole.vend.modules.stocktake.mapper.StocktakeMapper;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-4 盘点模块集成测试(vend_test_stocktake 独立库,每例事务回滚)。
 *
 * 覆盖验收清单:①快照账面(仓库Σledger/机器推算两源) ②只录差异未录视同相符
 * ③原因必选 ④盘盈盘亏单自动生成并过账(库存修正) ⑤机器盘点落锚点+推算账自愈
 * ⑥>¥50 老板确认守卫 ⑦锁账期守卫 ⑧损耗统计聚合 + 机器盘亏配对退库(仓库净不变)。
 */
@ActiveProfiles("test-stocktake")
class StocktakeFlowTest extends BaseIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private StocktakeService stocktakeService;
    @Autowired
    private StocktakeMapper stocktakeMapper;
    @Autowired
    private StocktakeItemMapper itemMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private MachineMapper machineMapper;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private DocItemMapper docItemMapper;
    @Autowired
    private MachineStockSnapshotMapper snapshotMapper;
    @Autowired
    private PeriodLockService periodLockService;

    // ============================== 造数工具 ==============================

    private Product product(String name) {
        Product p = new Product();
        p.setSkuCode("ST" + SEQ.incrementAndGet());
        p.setProductName(name);
        p.setUnit("件");
        p.setProductStatus("在售");
        productMapper.insert(p);
        return p;
    }

    private Machine machine(String name) {
        Machine m = new Machine();
        m.setMachineCode("STM" + SEQ.incrementAndGet());
        m.setMachineName(name);
        m.setDeviceId("STD" + SEQ.incrementAndGet());
        m.setMachineStatus("在线");
        machineMapper.insert(m);
        return m;
    }

    private Long createStocktake(String scope, Long machineId) {
        StocktakeDtos.CreateReq req = new StocktakeDtos.CreateReq();
        req.setScopeType(scope);
        req.setMachineId(machineId);
        req.setSourceTask("手动");
        return stocktakeService.create(req, OP);
    }

    private void record(Long stId, Long productId, String actual, String reason) {
        StocktakeDtos.SaveItemsReq req = new StocktakeDtos.SaveItemsReq();
        StocktakeDtos.ItemReq row = new StocktakeDtos.ItemReq();
        row.setProductId(productId);
        row.setActualQty(new BigDecimal(actual));
        row.setDiffReason(reason);
        req.setRows(Collections.singletonList(row));
        stocktakeService.saveItems(stId, req, OP);
    }

    private Map<Long, StocktakeItem> itemsOf(Long stId) {
        return itemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                        .eq(StocktakeItem::getStocktakeId, stId))
                .stream().collect(Collectors.toMap(StocktakeItem::getProductId, i -> i));
    }

    // ============================== ① 快照账面 ==============================

    @Test
    @DisplayName("①仓库快照:book_qty=Σledger(A=10,B=0),actual 默认=book(未录视同相符)")
    void warehouseBookSnapshot() {
        Product a = product("快照A");
        Product b = product("快照B");
        stockWarehouse(a.getId(), "10");

        Long stId = createStocktake(Stocktake.SCOPE_WAREHOUSE, null);
        Map<Long, StocktakeItem> items = itemsOf(stId);
        assertEquals(0, items.get(a.getId()).getBookQty().compareTo(new BigDecimal("10")), "A 账面=Σledger=10");
        assertEquals(0, items.get(b.getId()).getBookQty().compareTo(BigDecimal.ZERO), "B 无流水账面=0");
        assertEquals(0, items.get(a.getId()).getActualQty().compareTo(new BigDecimal("10")), "实盘默认=账面");
        assertEquals(0, items.get(a.getId()).getDiffQty().signum(), "默认差异=0");
    }

    @Test
    @DisplayName("①机器快照:book_qty=锚点10+转移5−出货2=13(推算源)")
    void machineBookSnapshot() {
        Product p = product("机器快照品");
        Machine m = machine("快照机");
        LocalDate day = LocalDate.now();
        stockWarehouse(p.getId(), "100");
        stockService.recordMachineSnapshot(m.getId(), p.getId(), "A1", new BigDecimal("10"),
                MachineStockSnapshot.SRC_BACKEND_PAGE, day.atTime(8, 0), OP);
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), "导入", day, false,
                day.atTime(9, 0), new Object[]{p.getId(), "5", "3.5"});
        insertSale(m.getId(), p.getId(), "2", "正常", day.atTime(10, 0));

        Long stId = createStocktake(Stocktake.SCOPE_MACHINE, m.getId());
        Map<Long, StocktakeItem> items = itemsOf(stId);
        assertEquals(0, items.get(p.getId()).getBookQty().compareTo(new BigDecimal("13")),
                "机器账面=锚点+增量推算=13");
    }

    // ============================== ②③ 只录差异 / 原因必选 ==============================

    @Test
    @DisplayName("②只录差异:仅录 A,B 未录=视同相符;确认后仅生成 1 行盘亏、B 不受影响")
    void onlyDiffRecordedOthersUntouched() {
        Product a = product("差异A");
        Product b = product("相符B");
        stockWarehouse(a.getId(), "10");
        stockWarehouse(b.getId(), "6");

        Long stId = createStocktake(Stocktake.SCOPE_WAREHOUSE, null);
        record(stId, a.getId(), "8", StocktakeItem.REASON_EXPIRE);
        stocktakeService.submit(stId, OP);
        StocktakeDtos.ConfirmResp resp = stocktakeService.confirm(stId, OP, "员工", false, null);

        assertNull(resp.getGainDocId(), "无盘盈行不生成盘盈单");
        assertNotNull(resp.getLossDocId(), "盘亏单已生成");
        List<DocItem> lossItems = docItemMapper.selectList(new LambdaQueryWrapper<DocItem>()
                .eq(DocItem::getDocId, resp.getLossDocId()));
        assertEquals(1, lossItems.size(), "只有录了差异的 A 进盘亏单");
        assertEquals(a.getId(), lossItems.get(0).getProductId());
        assertEquals(0, stockService.getWarehouseStock(a.getId()).compareTo(new BigDecimal("8")),
                "A 仓库账修正为实盘 8");
        assertEquals(0, stockService.getWarehouseStock(b.getId()).compareTo(new BigDecimal("6")),
                "B 未录=视同相符,库存不动");
    }

    @Test
    @DisplayName("③原因必选:差异行不带原因 → 录入被拒;原因非法 → 被拒")
    void reasonRequiredOnDiff() {
        Product a = product("原因必选品");
        stockWarehouse(a.getId(), "10");
        Long stId = createStocktake(Stocktake.SCOPE_WAREHOUSE, null);

        BizException e1 = assertThrows(BizException.class, () -> record(stId, a.getId(), "9", null));
        assertTrue(e1.getMessage().contains("差异行必选原因"), e1.getMessage());
        BizException e2 = assertThrows(BizException.class, () -> record(stId, a.getId(), "9", "随便写的"));
        assertTrue(e2.getMessage().contains("非法差异原因"), e2.getMessage());
        // 相符行(actual=book)不需要原因
        assertDoesNotThrow(() -> record(stId, a.getId(), "10", null));
    }

    // ============================== ④ 盘盈盘亏过账 ==============================

    @Test
    @DisplayName("④仓库盘盈+盘亏:A 10→12 盘盈+2、B 5→3 盘亏−2,两单自动确认过账,库存=实盘")
    void warehouseGainLossPosting() {
        Product a = product("盘盈品");
        Product b = product("盘亏品");
        stockWarehouse(a.getId(), "10");
        stockWarehouse(b.getId(), "5");

        Long stId = createStocktake(Stocktake.SCOPE_WAREHOUSE, null);
        StocktakeDtos.SaveItemsReq req = new StocktakeDtos.SaveItemsReq();
        StocktakeDtos.ItemReq r1 = new StocktakeDtos.ItemReq();
        r1.setProductId(a.getId());
        r1.setActualQty(new BigDecimal("12"));
        r1.setDiffReason(StocktakeItem.REASON_COUNT_ERROR);
        StocktakeDtos.ItemReq r2 = new StocktakeDtos.ItemReq();
        r2.setProductId(b.getId());
        r2.setActualQty(new BigDecimal("3"));
        r2.setDiffReason(StocktakeItem.REASON_SWALLOW);
        req.getRows().add(r1);
        req.getRows().add(r2);
        stocktakeService.saveItems(stId, req, OP);
        stocktakeService.submit(stId, OP);
        StocktakeDtos.ConfirmResp resp = stocktakeService.confirm(stId, OP, "员工", false, null);

        DocHead gain = docHeadMapper.selectById(resp.getGainDocId());
        DocHead loss = docHeadMapper.selectById(resp.getLossDocId());
        assertEquals(DocType.GAIN_IN, gain.getDocType());
        assertEquals(DocType.LOSS_OUT, loss.getDocType());
        assertEquals(DocStatus.CONFIRMED, gain.getDocStatus(), "盘盈单自动过账");
        assertEquals(DocStatus.CONFIRMED, loss.getDocStatus(), "盘亏单自动过账");
        assertEquals(0, stockService.getWarehouseStock(a.getId()).compareTo(new BigDecimal("12")));
        assertEquals(0, stockService.getWarehouseStock(b.getId()).compareTo(new BigDecimal("3")));
        // 差异金额按移动加权成本(3.5):−2×3.5=−7
        StocktakeItem bItem = itemsOf(stId).get(b.getId());
        assertEquals(0, bItem.getDiffAmount().compareTo(new BigDecimal("-7.00")),
                "盘亏差异金额=diff×加权成本=−7");
        // 盘点单终态 + 双单挂接
        Stocktake st = stocktakeMapper.selectById(stId);
        assertEquals(Stocktake.ST_DONE, st.getStStatus());
        assertEquals(resp.getGainDocId(), st.getGainDocId());
        assertEquals(resp.getLossDocId(), st.getLossDocId());
    }

    @Test
    @DisplayName("④机器盘亏(吞货):退库+盘亏配对过账——机器账=实盘、仓库账净不变、损耗入仓库亏行")
    void machineLossPairKeepsWarehouseNet() {
        Product p = product("机器盘亏品");
        Machine m = machine("盘亏机");
        LocalDate day = LocalDate.now();
        stockWarehouse(p.getId(), "100");
        stockService.recordMachineSnapshot(m.getId(), p.getId(), "A1", new BigDecimal("10"),
                MachineStockSnapshot.SRC_BACKEND_PAGE, day.atTime(8, 0), OP);

        Long stId = createStocktake(Stocktake.SCOPE_MACHINE, m.getId());
        record(stId, p.getId(), "9", StocktakeItem.REASON_SWALLOW);
        stocktakeService.submit(stId, OP);
        StocktakeDtos.ConfirmResp resp = stocktakeService.confirm(stId, OP, "员工", false, null);

        assertNotNull(resp.getReturnDocId(), "配对退库单已生成");
        assertNotNull(resp.getLossDocId(), "盘亏单已生成");
        assertEquals(DocType.RETURN_BACK, docHeadMapper.selectById(resp.getReturnDocId()).getDocType());
        assertEquals(DocType.LOSS_OUT, docHeadMapper.selectById(resp.getLossDocId()).getDocType());
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("9")),
                "机器账=实盘 9(锚点校准)");
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("100")),
                "仓库账净不变(退库+1 盘亏−1):机器的货不亏到仓库头上");
        // 锚点已落(source=盘点)
        List<MachineStockSnapshot> snaps = snapshotMapper.selectList(
                new LambdaQueryWrapper<MachineStockSnapshot>()
                        .eq(MachineStockSnapshot::getMachineId, m.getId())
                        .eq(MachineStockSnapshot::getProductId, p.getId())
                        .eq(MachineStockSnapshot::getSnapshotSource, MachineStockSnapshot.SRC_STOCKTAKE));
        assertEquals(1, snaps.size(), "机器盘点确认必落快照锚点");
        assertEquals(0, snaps.get(0).getQty().compareTo(new BigDecimal("9")));
    }

    // ============================== ⑤ 锚点自愈(M1-9 红灯) ==============================

    @Test
    @DisplayName("⑤锚点自愈:缺锚点机器推算为负(−3)→ 盘点实盘 10(盘点错误,不开财务单)→ 推算恢复 10")
    void anchorSelfHealsNegativeInference() {
        Product p = product("缺锚点品");
        Machine m = machine("红灯机");
        stockWarehouse(p.getId(), "50");
        // 只有销售、没有任何锚点/转移 → 推算 = 0 − 3 = −3(M1-9 的 67 红灯场景)
        insertSale(m.getId(), p.getId(), "3", "正常", LocalDateTime.now().minusHours(2));
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("-3")),
                "缺锚点机器推算为负=红灯");

        Long stId = createStocktake(Stocktake.SCOPE_MACHINE, m.getId());
        assertEquals(0, itemsOf(stId).get(p.getId()).getBookQty().compareTo(new BigDecimal("-3")),
                "账面快照如实存负数");
        record(stId, p.getId(), "10", StocktakeItem.REASON_COUNT_ERROR);
        stocktakeService.submit(stId, OP);
        StocktakeDtos.ConfirmResp resp = stocktakeService.confirm(stId, OP, "员工", false, null);

        assertNull(resp.getLossDocId(), "账错类原因(盘点错误)只校锚点,不开财务单");
        assertNull(resp.getGainDocId(), "机器盘盈由锚点吸收,不虚增资产");
        assertTrue(resp.getAnchorCount() >= 1);
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("10")),
                "锚点自愈:推算账恢复=实盘 10");
        // 自愈后再有销售,增量推算继续工作
        insertSale(m.getId(), p.getId(), "2", "正常", LocalDateTime.now().plusSeconds(1));
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("8")),
                "锚点后增量继续:10−2=8");
    }

    // ============================== ⑥ >¥50 老板确认 ==============================

    @Test
    @DisplayName("⑥>¥50 守卫:盘亏 20 件×3.5=¥70 → 员工确认被拒;老板角色放行")
    void bossConfirmThreshold() {
        Product p = product("大额盘亏品");
        stockWarehouse(p.getId(), "30");
        Long stId = createStocktake(Stocktake.SCOPE_WAREHOUSE, null);
        record(stId, p.getId(), "10", StocktakeItem.REASON_THEFT);
        stocktakeService.submit(stId, OP);

        BizException e = assertThrows(BizException.class,
                () -> stocktakeService.confirm(stId, OP, "员工", false, null));
        assertTrue(e.getMessage().contains("¥50") || e.getMessage().contains("老板"), e.getMessage());

        StocktakeDtos.ConfirmResp resp = stocktakeService.confirm(stId, OP, PeriodLockService.ROLE_BOSS, false, null);
        assertNotNull(resp.getLossDocId());
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("10")));
    }

    // ============================== ⑦ 锁账期守卫 ==============================

    @Test
    @DisplayName("⑦锁账守卫:快照月已锁 → 确认被拒;老板越权+备注放行")
    void periodLockGuardOnConfirm() {
        Product p = product("锁账期品");
        stockWarehouse(p.getId(), "5");
        Long stId = createStocktake(Stocktake.SCOPE_WAREHOUSE, null);
        stocktakeService.submit(stId, OP);

        // 把快照时间改到上月并锁上月(锁账只能锁过去的月)
        String lastMonth = YearMonth.now().minusMonths(1).format(PERIOD);
        Stocktake st = stocktakeMapper.selectById(stId);
        st.setSnapshotTime(st.getSnapshotTime().minusMonths(1));
        stocktakeMapper.updateById(st);
        periodLockService.lock(lastMonth, OP, "M2-4 锁账守卫测试");

        BizException e = assertThrows(BizException.class,
                () -> stocktakeService.confirm(stId, OP, "员工", false, null));
        assertTrue(e.getMessage().contains("已锁账"), e.getMessage());

        StocktakeDtos.ConfirmResp resp = stocktakeService.confirm(stId, OP,
                PeriodLockService.ROLE_BOSS, true, "月盘补确认");
        assertEquals(stId, resp.getStocktakeId());
        assertEquals(Stocktake.ST_DONE, stocktakeMapper.selectById(stId).getStStatus());
    }

    // ============================== ⑧ 损耗统计 ==============================

    @Test
    @DisplayName("⑧损耗统计:仓库过期−2(−¥7)+机器吞货−1(−¥3.5)→ 按原因×月份聚合正确")
    void lossStatsAggregation() {
        String month = YearMonth.now().format(PERIOD);
        // 仓库:过期报损 −2 × 3.5
        Product a = product("统计过期品");
        stockWarehouse(a.getId(), "10");
        Long st1 = createStocktake(Stocktake.SCOPE_WAREHOUSE, null);
        record(st1, a.getId(), "8", StocktakeItem.REASON_EXPIRE);
        stocktakeService.submit(st1, OP);
        stocktakeService.confirm(st1, OP, "员工", false, null);
        // 机器:吞货 −1 × 3.5
        Product b = product("统计吞货品");
        Machine m = machine("统计机");
        stockWarehouse(b.getId(), "20");
        stockService.recordMachineSnapshot(m.getId(), b.getId(), "B1", new BigDecimal("5"),
                MachineStockSnapshot.SRC_BACKEND_PAGE, LocalDateTime.now().minusHours(1), OP);
        Long st2 = createStocktake(Stocktake.SCOPE_MACHINE, m.getId());
        record(st2, b.getId(), "4", StocktakeItem.REASON_SWALLOW);
        stocktakeService.submit(st2, OP);
        stocktakeService.confirm(st2, OP, "员工", false, null);

        List<StocktakeDtos.LossStatRow> stats = stocktakeService.lossStats(3);
        StocktakeDtos.LossStatRow expire = stats.stream().filter(r -> month.equals(r.getMonth())
                && StocktakeItem.REASON_EXPIRE.equals(r.getReason())).findFirst().orElse(null);
        StocktakeDtos.LossStatRow swallow = stats.stream().filter(r -> month.equals(r.getMonth())
                && StocktakeItem.REASON_SWALLOW.equals(r.getReason())).findFirst().orElse(null);
        assertNotNull(expire, "过期报损聚合行在");
        assertNotNull(swallow, "吞货掉货聚合行在");
        assertTrue(expire.getQty().compareTo(new BigDecimal("2")) >= 0, "过期件数≥2(本例贡献2)");
        assertTrue(expire.getAmount().compareTo(new BigDecimal("7")) >= 0, "过期成本额≥7(2×3.5)");
        assertTrue(swallow.getQty().compareTo(BigDecimal.ONE) >= 0, "吞货件数≥1");
        assertTrue(swallow.getAmount().compareTo(new BigDecimal("3.5")) >= 0, "吞货成本额≥3.5");
    }

    // ============================== 附加守卫 ==============================

    @Test
    @DisplayName("附:同范围禁双开;未提交不能确认;已完成不能作废")
    void lifecycleGuards() {
        Product p = product("守卫品");
        stockWarehouse(p.getId(), "5");
        Long stId = createStocktake(Stocktake.SCOPE_WAREHOUSE, null);

        BizException dup = assertThrows(BizException.class,
                () -> createStocktake(Stocktake.SCOPE_WAREHOUSE, null));
        assertTrue(dup.getMessage().contains("已有进行中的盘点单"), dup.getMessage());

        BizException notSubmitted = assertThrows(BizException.class,
                () -> stocktakeService.confirm(stId, OP, "员工", false, null));
        assertTrue(notSubmitted.getMessage().contains("请先提交"), notSubmitted.getMessage());

        stocktakeService.submit(stId, OP);
        stocktakeService.confirm(stId, OP, "员工", false, null);
        BizException doneCancel = assertThrows(BizException.class,
                () -> stocktakeService.cancel(stId, OP));
        assertTrue(doneCancel.getMessage().contains("不能作废"), doneCancel.getMessage());
    }
}
