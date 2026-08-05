package top.aole.vend.regression;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.ProductService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.purchase.service.PurchaseReceiptService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景9:淘汰清仓剩货(审计 P2-10)。
 *
 * 审计结论:"归零才停售"与仓库残余互为死锁 → 修正:新增"清仓中"商品状态,
 * 禁采购/禁补货建议,允许上架/退货/报损;仓库>0 超30天三选一提示。
 *
 * M1 范围:商品状态流转(在售/清仓中/停售)+ clearance_since 留痕 + **清仓中禁采购守卫**
 *          (订货单/采购入库两个入口都拦)+ 死锁解除(清仓中允许上架/退货/报损)。
 * M2 范围:清仓中不进补货建议;仓库残余>30 天三选一提示(退供/报损/换机促销)。
 */
class Scenario09ClearanceTest extends RegressionSupport {

    @Autowired
    private ProductService productService;
    @Autowired
    private PurchaseOrderService poService;
    @Autowired
    private PurchaseReceiptService receiptService;

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
    @Disabled("M2-pending:补货建议引擎(replenish_plan)未实现——缺\"清仓中商品不进补货建议\"的生成过滤断言")
    void clearanceProductExcludedFromReplenishPlan() {
        // M2 实装后:清仓中商品跑建议生成 → replenish_plan 不出现该 SKU
    }

    @Test
    @Disabled("M2-pending:清仓中仓库>0 超30天三选一提示(退供/报损/换机促销)为驾驶舱/任务引擎逻辑,M2/M4 实装")
    void clearanceOver30DaysThreeChoicePrompt() {
        // clearance_since 字段已在(本类第一例已验),提示逻辑挂 M2
    }
}
