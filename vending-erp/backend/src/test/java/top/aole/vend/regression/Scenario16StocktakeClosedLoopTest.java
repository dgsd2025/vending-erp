package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;
import top.aole.vend.modules.stock.mapper.MachineStockSnapshotMapper;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.mapper.StocktakeItemMapper;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 场景16:盘点闭环(缺锚点自愈 → 次日推算 → 损耗统计,跨 stocktake/stock/doc 三模块)。
 *
 * 链路:缺锚点机器(只有销售,推算为负=M1-9 红灯)→ 机器盘点(账面如实存负数)
 *      → 确认:账错行只校锚点不开财务单、真损耗行"退库+盘亏"配对(仓库净不变)
 *      → 锚点自愈 → 次日销售增量推算继续正确 → 损耗统计按原因聚合。
 */
class Scenario16StocktakeClosedLoopTest extends RegressionSupport {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private StocktakeService stocktakeService;
    @Autowired
    private StocktakeItemMapper stocktakeItemMapper;
    @Autowired
    private MachineStockSnapshotMapper snapshotMapper;
    @Autowired
    private DocHeadMapper docHeadMapper;

    @Test
    @DisplayName("盘点闭环:缺锚点推算−4→盘点自愈到8;吞货−1配对退库+盘亏(仓库净不变)→次日销售推算正确→损耗统计聚合")
    void anchorSelfHealNextDayInferenceAndLossStats() {
        LocalDateTime now = LocalDateTime.now();
        Machine m = machine("盘点闭环机");
        Product pHeal = product("缺锚点酸奶", null, null);   // 缺锚点:推算为负 → 自愈
        Product pLoss = product("吞货咖啡", null, null);     // 有锚点:真损耗 → 配对单
        stockWarehouse(pHeal.getId(), "50");
        stockWarehouse(pLoss.getId(), "50");

        // 缺锚点:只有销售没有任何快照/转移 → 推算 0−4=−4(M1-9 红灯)
        insertSale(m.getId(), pHeal.getId(), "4", "正常", now.minusHours(3));
        assertEquals(0, stockService.getMachineStock(m.getId(), pHeal.getId())
                .compareTo(new BigDecimal("-4")), "缺锚点机器推算为负=红灯");
        // 有锚点:2 小时前后台缺货页快照 10
        stockService.recordMachineSnapshot(m.getId(), pLoss.getId(), "B1", new BigDecimal("10"),
                MachineStockSnapshot.SRC_BACKEND_PAGE, now.minusHours(2), OP);

        // ① 机器盘点:账面快照如实存负数
        StocktakeDtos.CreateReq createReq = new StocktakeDtos.CreateReq();
        createReq.setScopeType(Stocktake.SCOPE_MACHINE);
        createReq.setMachineId(m.getId());
        createReq.setSourceTask("手动");
        Long stId = stocktakeService.create(createReq, OP);
        Map<Long, StocktakeItem> book = itemsOf(stId);
        assertEquals(0, book.get(pHeal.getId()).getBookQty().compareTo(new BigDecimal("-4")),
                "账面快照如实存负数,不粉饰");
        assertEquals(0, book.get(pLoss.getId()).getBookQty().compareTo(new BigDecimal("10")));

        // ② 录实盘(整包):pHeal 实盘 8(盘点错误=账错,不算损耗);pLoss 实盘 9(吞货=真损耗)
        StocktakeDtos.SaveItemsReq saveReq = new StocktakeDtos.SaveItemsReq();
        StocktakeDtos.ItemReq r1 = new StocktakeDtos.ItemReq();
        r1.setProductId(pHeal.getId());
        r1.setActualQty(new BigDecimal("8"));
        r1.setDiffReason(StocktakeItem.REASON_COUNT_ERROR);
        StocktakeDtos.ItemReq r2 = new StocktakeDtos.ItemReq();
        r2.setProductId(pLoss.getId());
        r2.setActualQty(new BigDecimal("9"));
        r2.setDiffReason(StocktakeItem.REASON_SWALLOW);
        saveReq.getRows().add(r1);
        saveReq.getRows().add(r2);
        stocktakeService.saveItems(stId, saveReq, OP);
        stocktakeService.submit(stId, OP);

        // ③ 确认:账错行不开财务单;真损耗行"退库+盘亏"配对;两 SKU 都落锚点
        StocktakeDtos.ConfirmResp resp = stocktakeService.confirm(stId, OP, "员工", false, null);
        assertNull(resp.getGainDocId(), "机器盘盈由锚点吸收,不虚增资产");
        assertNotNull(resp.getReturnDocId(), "吞货配对退库单生成");
        assertNotNull(resp.getLossDocId(), "吞货盘亏单生成");
        assertEquals(DocType.RETURN_BACK, docHeadMapper.selectById(resp.getReturnDocId()).getDocType());
        assertEquals(DocType.LOSS_OUT, docHeadMapper.selectById(resp.getLossDocId()).getDocType());
        assertEquals(2, resp.getAnchorCount(), "实盘≥0 的两行全部落锚点");
        List<MachineStockSnapshot> anchors = snapshotMapper.selectList(
                new LambdaQueryWrapper<MachineStockSnapshot>()
                        .eq(MachineStockSnapshot::getMachineId, m.getId())
                        .eq(MachineStockSnapshot::getSnapshotSource, MachineStockSnapshot.SRC_STOCKTAKE));
        assertEquals(2, anchors.size(), "锚点 source=盘点");

        // ④ 锚点自愈:推算账=实盘;仓库净不变(机器的货不亏到仓库头上)
        assertEquals(0, stockService.getMachineStock(m.getId(), pHeal.getId())
                .compareTo(new BigDecimal("8")), "缺锚点自愈:−4 → 实盘 8");
        assertEquals(0, stockService.getMachineStock(m.getId(), pLoss.getId())
                .compareTo(new BigDecimal("9")), "吞货校准:10 → 实盘 9");
        assertEquals(0, stockService.getWarehouseStock(pHeal.getId()).compareTo(new BigDecimal("50")),
                "账错行不动仓库");
        assertEquals(0, stockService.getWarehouseStock(pLoss.getId()).compareTo(new BigDecimal("50")),
                "退库+1 盘亏−1 配对:仓库净不变");

        // ⑤ 次日销售 → 锚点后增量推算继续正确
        LocalDateTime tomorrow = now.plusDays(1);
        insertSale(m.getId(), pHeal.getId(), "2", "正常", tomorrow);
        insertSale(m.getId(), pLoss.getId(), "3", "正常", tomorrow);
        assertEquals(0, stockService.getMachineStock(m.getId(), pHeal.getId())
                .compareTo(new BigDecimal("6")), "次日推算 8−2=6");
        assertEquals(0, stockService.getMachineStock(m.getId(), pLoss.getId())
                .compareTo(new BigDecimal("6")), "次日推算 9−3=6");

        // ⑥ 损耗统计:吞货行进统计(件数≥1,成本额≥3.5);豁免口径由查询排除
        String month = YearMonth.now().format(PERIOD);
        List<StocktakeDtos.LossStatRow> stats = stocktakeService.lossStats(3);
        StocktakeDtos.LossStatRow swallow = stats.stream()
                .filter(r -> month.equals(r.getMonth())
                        && StocktakeItem.REASON_SWALLOW.equals(r.getReason()))
                .findFirst().orElse(null);
        assertNotNull(swallow, "吞货损耗聚合行在(原因×月份)");
        assertTrue(swallow.getQty().compareTo(BigDecimal.ONE) >= 0, "吞货件数≥1(本例贡献1)");
        assertTrue(swallow.getAmount().compareTo(new BigDecimal("3.5")) >= 0, "吞货成本额≥1×3.5");
    }

    private Map<Long, StocktakeItem> itemsOf(Long stId) {
        return stocktakeItemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                        .eq(StocktakeItem::getStocktakeId, stId))
                .stream().collect(Collectors.toMap(StocktakeItem::getProductId, i -> i));
    }
}
