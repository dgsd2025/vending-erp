package top.aole.vend.regression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrder;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.purchase.service.PurchaseReceiptService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景2:拿货日(审计结论:断——无采购订单 → 公式里的"在途库存"没有数据生产者;P0-5 修复)。
 *
 * M1 已落地并全测(端到端):轻量订货单(草稿不进账)→ 下单计在途 →
 * 收货单从订单带"应收"列(expect_qty)→ 部分到货回写 qty_received/在途递减 →
 * 全收完成/在途归零 → 超期未到黄灯。
 */
class Scenario02PurchaseDayTest extends RegressionSupport {

    private static final Long SUPPLIER = 601L;

    @Autowired
    private PurchaseOrderService poService;
    @Autowired
    private PurchaseReceiptService receiptService;

    private Long po(Long productId, String qty, String price, LocalDate expectDate) {
        PoCreateReq req = new PoCreateReq();
        req.setSupplierId(SUPPLIER);
        req.setExpectDate(expectDate);
        PoCreateReq.Item item = new PoCreateReq.Item();
        item.setProductId(productId);
        item.setQtyOrdered(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        req.setItems(Collections.singletonList(item));
        return poService.create(req, OPERATOR);
    }

    @Test
    @DisplayName("在途数据生产者(修断点):草稿不进账在途0;下单后在途=订购量;取消后归零")
    void inTransitProducedOnlyByPlacedOrder() {
        Product p = product("整箱矿泉水", null, null);
        Long poId = po(p.getId(), "24", "1.2", LocalDate.now().plusDays(3));
        assertEquals(0, poService.inTransit(p.getId()).compareTo(BigDecimal.ZERO), "草稿级不进账,在途0");

        poService.place(poId, OPERATOR);
        assertEquals(0, poService.inTransit(p.getId()).compareTo(new BigDecimal("24")), "下单后在途=24");
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(BigDecimal.ZERO),
                "订货单永不碰库存流水");

        // 未收过货可取消 → 在途归零
        poService.cancel(poId, OPERATOR);
        assertEquals(0, poService.inTransit(p.getId()).compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("收货应收列+部分到货:收货单带 expect_qty=20;实收15→部分到货/在途5;补收5→已完成/在途0;库存=20")
    void receiptCarriesExpectQtyAndPartialFlow() {
        Product p = product("卤味豆干", null, null);
        Long poId = po(p.getId(), "20", "3.0", LocalDate.now().plusDays(2));
        poService.place(poId, OPERATOR);

        // 一键生成收货单:应收列=订购−已收=20
        Long receiptId = receiptService.createFromPo(poId, LocalDate.now(), OP);
        List<DocItem> items = docService.itemsOf(receiptId);
        assertEquals(1, items.size());
        assertEquals(0, items.get(0).getExpectQty().compareTo(new BigDecimal("20")), "应收列=20(P0-5)");
        assertNotNull(items.get(0).getPoItemId(), "带 po_item_id 供回写已收量");

        // 到货少了5瓶,按实收改成15("少5瓶按实收")
        ReceiptCreateReq edit = new ReceiptCreateReq();
        edit.setSupplierId(SUPPLIER);
        edit.setBizDate(LocalDate.now());
        edit.setPurchaseOrderId(poId);
        ReceiptCreateReq.Item item = new ReceiptCreateReq.Item();
        item.setProductId(p.getId());
        item.setQty(new BigDecimal("15"));
        item.setExpectQty(new BigDecimal("20"));
        item.setUnitPrice(new BigDecimal("3.0"));
        item.setPoItemId(items.get(0).getPoItemId());
        edit.setItems(Collections.singletonList(item));
        receiptService.updateDraft(receiptId, edit, OP);
        receiptService.confirm(receiptId, OP);

        assertEquals(PurchaseOrder.ST_PARTIAL, poService.mustGet(poId).getPoStatus(), "部分到货");
        assertEquals(0, poService.inTransit(p.getId()).compareTo(new BigDecimal("5")), "在途=订购20−已收15");
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("15")),
                "确认入库过账,仓库=实收15");

        // 补货到齐:再从订单生成收货单,应收=剩余5
        Long receipt2 = receiptService.createFromPo(poId, LocalDate.now(), OP);
        assertEquals(0, docService.itemsOf(receipt2).get(0).getExpectQty()
                .compareTo(new BigDecimal("5")), "第二张收货单应收=剩余5");
        receiptService.confirm(receipt2, OP);

        assertEquals(PurchaseOrder.ST_DONE, poService.mustGet(poId).getPoStatus(), "全收→已完成");
        assertEquals(0, poService.inTransit(p.getId()).compareTo(BigDecimal.ZERO), "在途归零");
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("20")));
    }

    @Test
    @DisplayName("超期黄灯:预计到货日已过且仍在等货 → overdue=true;收完灯灭")
    void overdueYellowFlag() {
        Product p = product("迟到的泡面", null, null);
        Long poId = po(p.getId(), "10", "2.0", LocalDate.now().minusDays(2));
        poService.place(poId, OPERATOR);
        assertTrue(poService.detail(poId).isOverdue(), "超期未到→黄灯");
        assertEquals(0, poService.detail(poId).getInTransitQty().compareTo(new BigDecimal("10")));

        Long receiptId = receiptService.createFromPo(poId, LocalDate.now(), OP);
        receiptService.confirm(receiptId, OP);
        assertFalse(poService.detail(poId).isOverdue(), "收完不再亮灯");
    }

    @Test
    @DisplayName("已收过货的订单不可取消,只能关闭余量(部分到货→已完成,在途放弃)")
    void closeRemainingInsteadOfCancelAfterReceipt() {
        Product p = product("断货的槟榔", null, null);
        Long poId = po(p.getId(), "10", "6.0", null);
        poService.place(poId, OPERATOR);
        Long receiptId = receiptService.createFromPo(poId, LocalDate.now(), OP);
        // 只收3
        ReceiptCreateReq edit = new ReceiptCreateReq();
        edit.setSupplierId(SUPPLIER);
        edit.setBizDate(LocalDate.now());
        edit.setPurchaseOrderId(poId);
        ReceiptCreateReq.Item item = new ReceiptCreateReq.Item();
        item.setProductId(p.getId());
        item.setQty(new BigDecimal("3"));
        item.setExpectQty(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("6.0"));
        item.setPoItemId(docService.itemsOf(receiptId).get(0).getPoItemId());
        edit.setItems(Collections.singletonList(item));
        receiptService.updateDraft(receiptId, edit, OP);
        receiptService.confirm(receiptId, OP);

        assertThrows(top.aole.vend.common.exception.BizException.class,
                () -> poService.cancel(poId, OPERATOR), "收过货不可取消");
        poService.closeRemaining(poId, OPERATOR);
        assertEquals(PurchaseOrder.ST_DONE, poService.mustGet(poId).getPoStatus());
        assertEquals(0, poService.inTransit(p.getId()).compareTo(BigDecimal.ZERO), "关闭余量后在途不再挂账");
    }
}
