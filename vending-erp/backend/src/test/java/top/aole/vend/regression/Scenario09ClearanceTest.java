package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.ProductService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.infrastructure.mapper.SlotMapper;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.purchase.service.PurchaseReceiptService;
import top.aole.vend.modules.replenish.domain.entity.ReplenishPlan;
import top.aole.vend.modules.replenish.mapper.ReplenishPlanMapper;
import top.aole.vend.modules.replenish.service.ReplenishEngine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景9:淘汰清仓剩货(审计 P2-10)。
 *
 * 审计结论:"归零才停售"与仓库残余互为死锁 → 修正:新增"清仓中"商品状态,
 * 禁采购/禁补货建议,允许上架/退货/报损;仓库>0 超30天三选一提示。
 *
 * M1 范围:商品状态流转(在售/清仓中/停售)+ clearance_since 留痕 + **清仓中禁采购守卫**
 *          (订货单/采购入库两个入口都拦)+ 死锁解除(清仓中允许上架/退货/报损)。
 * M2 范围(M2-8 已开):清仓中不进补货建议(ReplenishEngine.recalc 两侧过滤);
 *          仓库残余>30 天三选一提示(ReplenishEngine.clearanceAlerts 只读接口,退供/报损/换机促销)。
 */
class Scenario09ClearanceTest extends RegressionSupport {

    @Autowired
    private ProductService productService;
    @Autowired
    private PurchaseOrderService poService;
    @Autowired
    private PurchaseReceiptService receiptService;
    @Autowired
    private ReplenishEngine replenishEngine;
    @Autowired
    private ReplenishPlanMapper replenishPlanMapper;
    @Autowired
    private SlotMapper slotMapper;

    private static final Long SUPPLIER = 601L;

    @Test
    @DisplayName("状态流转:在售→清仓中记 clearance_since;恢复在售清空;非法状态拒绝")
    void statusTransitionKeepsClearanceSince() {
        Product p = product("淘汰卤味", null, null);
        Product after = productService.changeStatus(p.getId(), "清仓中", OPERATOR);
        assertEquals("清仓中", after.getProductStatus());
        assertEquals(LocalDate.now(), after.getClearanceSince(), "进入清仓中记日期(30天三选一的计时起点)");

        after = productService.changeStatus(p.getId(), "在售", OPERATOR);
        assertEquals("在售", after.getProductStatus());
        assertNull(after.getClearanceSince(), "恢复在售清空清仓日期");

        assertThrows(BizException.class,
                () -> productService.changeStatus(p.getId(), "已删除", OPERATOR), "只允许 在售/清仓中/停售");
    }

    @Test
    @DisplayName("清仓中禁采购①:订货单(purchase_order)带清仓中商品 → 建单拒绝")
    void clearanceProductBlockedFromPurchaseOrder() {
        Product p = product("清仓槟榔", null, null);
        productService.changeStatus(p.getId(), "清仓中", OPERATOR);

        PoCreateReq req = new PoCreateReq();
        req.setSupplierId(SUPPLIER);
        PoCreateReq.Item item = new PoCreateReq.Item();
        item.setProductId(p.getId());
        item.setQtyOrdered(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("5"));
        req.setItems(Collections.singletonList(item));

        BizException e = assertThrows(BizException.class, () -> poService.create(req, OPERATOR),
                "清仓中商品不许再下订货单(P2-10)");
        assertTrue(e.getMessage().contains("清仓中"), "报错说明原因:" + e.getMessage());
    }

    @Test
    @DisplayName("清仓中禁采购②:采购入库单(直录兜底通道)带清仓中商品 → 建单拒绝")
    void clearanceProductBlockedFromPurchaseReceipt() {
        Product p = product("清仓泡面", null, null);
        productService.changeStatus(p.getId(), "清仓中", OPERATOR);

        ReceiptCreateReq req = new ReceiptCreateReq();
        req.setSupplierId(SUPPLIER);
        req.setBizDate(LocalDate.now());
        ReceiptCreateReq.Item item = new ReceiptCreateReq.Item();
        item.setProductId(p.getId());
        item.setQty(new BigDecimal("5"));
        item.setUnitPrice(new BigDecimal("3"));
        req.setItems(Collections.singletonList(item));

        BizException e = assertThrows(BizException.class, () -> receiptService.createDirect(req, OP),
                "清仓中商品不许再录采购入库");
        assertTrue(e.getMessage().contains("清仓中"), "报错说明原因:" + e.getMessage());
    }

    @Test
    @DisplayName("死锁解除:清仓中仍允许 上架(转移)/退货(退库)/报损——货能出得去,不会僵死")
    void clearanceProductStillAllowsOutboundPaths() {
        Product p = product("清仓面包", null, null);
        Machine m = machine("清仓测试机");
        // 清仓前先备货(在售时采购合法)
        stockWarehouse(p.getId(), "20");
        productService.changeStatus(p.getId(), "清仓中", OPERATOR);

        // 上架:导入源转移单(唯一生产者口径)照常过账
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.now(), false, LocalDate.now().atTime(9, 0), new Object[]{p.getId(), "8", "3.5"});
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("12")));

        // 退货(机器→仓库退库)照常
        confirmedDoc(DocType.RETURN_BACK, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.now(), false, LocalDate.now().atTime(10, 0), new Object[]{p.getId(), "2", "3.5"});
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("14")));

        // 报损照常
        confirmedDoc(DocType.DAMAGE, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{p.getId(), "4", "3.5"});
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("10")));
    }

    @Test
    @DisplayName("对照:在售商品采购照常放行(守卫只拦清仓中)")
    void onSaleProductStillPurchasable() {
        Product p = product("在售可乐", null, null);
        PoCreateReq req = new PoCreateReq();
        req.setSupplierId(SUPPLIER);
        PoCreateReq.Item item = new PoCreateReq.Item();
        item.setProductId(p.getId());
        item.setQtyOrdered(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("2"));
        req.setItems(Collections.singletonList(item));
        assertNotNull(poService.create(req, OPERATOR));
        // 直录采购入库同样放行
        assertNotNull(stockWarehouse(p.getId(), "5"));
    }

    @Test
    @DisplayName("M2 清仓过滤:同机同销量的在售对照品出建议,清仓中商品机器侧/采购侧一行都不出")
    void clearanceProductExcludedFromReplenishPlan() {
        Machine m = machine("清仓建议机");
        Product onSale = product("在售对照水", null, null);
        Product clearing = product("清仓下架茶", null, null);
        slot(m.getId(), onSale.getId(), "A1", "20");
        slot(m.getId(), clearing.getId(), "A2", "20");
        // 两品同样有近 7 天日均 3 的销量(清仓品曾经也畅销)
        dailySales(m.getId(), onSale.getId(), 7, "3");
        dailySales(m.getId(), clearing.getId(), 7, "3");
        productService.changeStatus(clearing.getId(), "清仓中", OPERATOR);

        replenishEngine.recalc(OPERATOR);

        List<ReplenishPlan> control = plansOf(onSale.getId());
        assertFalse(control.isEmpty(), "在售对照品必须出建议(证明引擎真跑了)");
        assertTrue(control.stream().anyMatch(p -> ReplenishEngine.TYPE_MACHINE.equals(p.getPlanType())),
                "对照品机器侧建议在");
        assertTrue(plansOf(clearing.getId()).isEmpty(),
                "清仓中商品机器侧+采购侧都不进 replenish_plan(P2-10:清仓禁补货建议)");
    }

    @Test
    @DisplayName("M2 三选一提示:清仓中仓库>0 超30天 → 退供/报损/换机促销;未满30天或仓库=0 不提示")
    void clearanceOver30DaysThreeChoicePrompt() {
        // 命中:超 30 天且仓库还有 8 件
        Product stale = product("清仓卤蛋", null, null);
        stockWarehouse(stale.getId(), "8");
        productService.changeStatus(stale.getId(), "清仓中", OPERATOR);
        backdateClearance(stale.getId(), 31);
        // 不命中①:刚清仓 5 天(30 天计时未到)
        Product fresh = product("刚清仓薯片", null, null);
        stockWarehouse(fresh.getId(), "5");
        productService.changeStatus(fresh.getId(), "清仓中", OPERATOR);
        backdateClearance(fresh.getId(), 5);
        // 不命中②:超 30 天但仓库已清零(货出完了,不用再催)
        Product empty = product("清空可乐", null, null);
        productService.changeStatus(empty.getId(), "清仓中", OPERATOR);
        backdateClearance(empty.getId(), 40);

        List<Map<String, Object>> alerts = replenishEngine.clearanceAlerts();

        Map<String, Object> hit = alerts.stream()
                .filter(a -> stale.getId().equals(a.get("productId"))).findFirst().orElse(null);
        assertNotNull(hit, "清仓中+仓库>0+超30天 必须提示");
        assertEquals(0, new BigDecimal(hit.get("warehouseQty").toString())
                .compareTo(new BigDecimal("8")), "提示带仓库残余数量");
        assertTrue(((Number) hit.get("daysInClearance")).longValue() > 30, "提示带清仓天数");
        assertEquals(Arrays.asList("退供", "报损", "换机促销"), hit.get("choices"),
                "三选一固定选项:退供/报损/换机促销");
        String msg = String.valueOf(hit.get("message"));
        assertTrue(msg.contains("退供") && msg.contains("报损") && msg.contains("换机促销"),
                "人话提示三个出路都点名:" + msg);

        assertFalse(alerts.stream().anyMatch(a -> fresh.getId().equals(a.get("productId"))),
                "未满 30 天不提示");
        assertFalse(alerts.stream().anyMatch(a -> empty.getId().equals(a.get("productId"))),
                "仓库=0 不提示(残余已处理完)");
    }

    // ============================== M2 造数辅助 ==============================

    /** 货道(正常状态,绑 SKU):机器侧 par level 的容量来源 */
    private void slot(Long machineId, Long productId, String slotNo, String capacity) {
        Slot s = new Slot();
        s.setMachineId(machineId);
        s.setProductId(productId);
        s.setSlotNo(slotNo);
        s.setCapacity(new BigDecimal(capacity));
        s.setCurrentQty(BigDecimal.ZERO);
        s.setSlotStatus("正常");
        slotMapper.insert(s);
    }

    /** 连续 days 天每天 qty 件(截至昨天,正常口径) */
    private void dailySales(Long machineId, Long productId, int days, String qty) {
        LocalDate end = LocalDate.now().minusDays(1);
        for (int i = 0; i < days; i++) {
            insertSale(machineId, productId, qty, "正常",
                    LocalDateTime.of(end.minusDays(i), LocalTime.NOON));
        }
    }

    /** 把清仓起始日回拨 N 天(模拟清仓已挂了 N 天) */
    private void backdateClearance(Long productId, int days) {
        jdbc.update("UPDATE yc_vend_product SET clearance_since=? WHERE id=?",
                java.sql.Date.valueOf(LocalDate.now().minusDays(days)), productId);
    }

    private List<ReplenishPlan> plansOf(Long productId) {
        return replenishPlanMapper.selectList(new LambdaQueryWrapper<ReplenishPlan>()
                .eq(ReplenishPlan::getProductId, productId));
    }
}
