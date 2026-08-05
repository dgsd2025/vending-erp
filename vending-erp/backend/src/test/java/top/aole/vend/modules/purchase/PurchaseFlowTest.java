package top.aole.vend.modules.purchase;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.PriceLog;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.PriceLogMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrder;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import top.aole.vend.modules.purchase.mapper.PurchaseOrderItemMapper;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.purchase.service.PurchaseReceiptListener;
import top.aole.vend.modules.purchase.service.PurchaseReceiptService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-4 采购链路集成测试(独立 vend_test_purchase 库,@Transactional 每例回滚):
 * 下单→部分收货(在途=差值)→再收全(订单完成)→重复确认幂等拒绝→无订单直录→比价/价史/进价留痕→超期黄灯→取消。
 */
@ActiveProfiles("test-purchase")
class PurchaseFlowTest extends BaseIntegrationTest {

    @Autowired
    private PurchaseOrderService poService;
    @Autowired
    private PurchaseReceiptService receiptService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private PurchaseOrderItemMapper poItemMapper;
    @Autowired
    private StockLedgerMapper stockLedgerMapper;
    @Autowired
    private PriceLogMapper priceLogMapper;

    private static final String OP_NAME = "测试小邱";

    private Long productA;
    private Long productB;
    private Long supplierId;

    @BeforeEach
    void initMasterData() {
        productA = createProduct("东鹏特饮500ml");
        productB = createProduct("景田纯净水560ml");
        supplierId = createSupplier("陈老板");
    }

    // ============ 用例1:下单→在途;从订货单生成收货单带应收列 ============

    @Test
    void placeOrder_inTransitAndExpectQty() {
        Long poId = createPo(supplierId, LocalDate.now().plusDays(3),
                item(productA, "48", "3.40"), item(productB, "24", "0.80"));
        // 草稿不计在途
        assertEquals(0, poService.inTransit(productA).compareTo(BigDecimal.ZERO));
        poService.place(poId, OP_NAME);
        // 已下单:在途 = 订购全量
        assertEquals(0, poService.inTransit(productA).compareTo(new BigDecimal("48")));
        assertEquals(0, poService.inTransit(productB).compareTo(new BigDecimal("24")));

        // 一键生成收货草稿:应收列=订购-已收
        Long docId = receiptService.createFromPo(poId, LocalDate.now(), OP);
        Map<String, Object> detail = receiptService.receiptDetail(docId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertEquals(2, items.size());
        assertEquals(0, new BigDecimal(items.get(0).get("expectQty").toString())
                .compareTo(new BigDecimal("48")));
    }

    // ============ 用例2:部分收货→在途=差值,订单=部分到货;再收全→已完成,在途=0 ============

    @Test
    void partialThenFullReceive() {
        Long poId = createPo(supplierId, null, item(productA, "48", "3.40"));
        poService.place(poId, OP_NAME);

        // 部分收货:应收48 实收 40(少8按实收)
        Long docId = receiptService.createDirect(receiptReq(supplierId, poId,
                rItem(productA, "40", "48", "3.40", poItemId(poId, productA))), OP);
        receiptService.confirm(docId, OP);

        assertEquals(PurchaseOrder.ST_PARTIAL, poService.mustGet(poId).getPoStatus());
        assertEquals(0, poService.inTransit(productA).compareTo(new BigDecimal("8")));
        // 仓库账过账 +40(stock 引擎联动)
        assertEquals(0, stockService.getWarehouseStock(productA).compareTo(new BigDecimal("40")));

        // 再收剩余 8 → 订单已完成,在途=0
        Long docId2 = receiptService.createFromPo(poId, LocalDate.now(), OP);
        receiptService.confirm(docId2, OP);
        assertEquals(PurchaseOrder.ST_DONE, poService.mustGet(poId).getPoStatus());
        assertEquals(0, poService.inTransit(productA).compareTo(BigDecimal.ZERO));
        assertEquals(0, stockService.getWarehouseStock(productA).compareTo(new BigDecimal("48")));
        // qty_received 回写到位
        PurchaseOrderItem poItem = poItemMapper.selectById(poItemId(poId, productA));
        assertEquals(0, poItem.getQtyReceived().compareTo(new BigDecimal("48")));
    }

    // ============ 用例3:重复确认幂等拒绝 ============

    @Test
    void duplicateConfirmRejected() {
        Long poId = createPo(supplierId, null, item(productA, "10", "3.40"));
        poService.place(poId, OP_NAME);
        Long docId = receiptService.createFromPo(poId, LocalDate.now(), OP);
        receiptService.confirm(docId, OP);
        // 第二次确认:状态机拒绝,qty_received 不被双写
        assertThrows(BizException.class, () -> receiptService.confirm(docId, OP));
        PurchaseOrderItem poItem = poItemMapper.selectById(poItemId(poId, productA));
        assertEquals(0, poItem.getQtyReceived().compareTo(new BigDecimal("10")));
        // 库存也只 +10 一次
        assertEquals(0, stockService.getWarehouseStock(productA).compareTo(new BigDecimal("10")));
    }

    // ============ 用例4:无订单直接录采购入库(兜底) ============

    @Test
    void directReceiptWithoutPo() {
        Long docId = receiptService.createDirect(receiptReq(supplierId, null,
                rItem(productA, "24", null, "3.50", null)), OP);
        receiptService.confirm(docId, OP);
        assertEquals(0, stockService.getWarehouseStock(productA).compareTo(new BigDecimal("24")));
        // 流水挂单据,doc_id 非空(库存只能被单据改)
        List<StockLedger> ledgers = stockLedgerMapper.selectList(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getProductId, productA));
        assertEquals(1, ledgers.size());
        assertEquals(docId, ledgers.get(0).getDocId());
        // 确认后单据状态=已确认
        assertEquals(DocStatus.CONFIRMED, docService.getDoc(docId).getHead().getDocStatus());
    }

    // ============ 用例5:采购价历史聚合 + 进价变动写 price_log ============

    @Test
    void priceHistoryAndPriceLog() {
        // 第一笔 3.40 × 24
        Long doc1 = receiptService.createDirect(receiptReq(supplierId, null,
                rItem(productA, "24", null, "3.40", null)), OP);
        receiptService.confirm(doc1, OP);
        // 第二笔 3.80 × 24(涨价)
        Long doc2 = receiptService.createDirect(receiptReq(supplierId, null,
                rItem(productA, "24", null, "3.80", null)), OP);
        receiptService.confirm(doc2, OP);

        // 价史聚合:最近价 3.80 / 最低 3.40 / 2 次
        List<Map<String, Object>> history = receiptService.priceHistory(productA, supplierId);
        assertEquals(1, history.size());
        Map<String, Object> row = history.get(0);
        assertEquals(0, new BigDecimal(row.get("lastPrice").toString()).compareTo(new BigDecimal("3.80")));
        assertEquals(0, new BigDecimal(row.get("minPrice").toString()).compareTo(new BigDecimal("3.40")));
        assertEquals(2L, Long.parseLong(row.get("buyCount").toString()));

        // 进价变动自动写 price_log(change_source=进价):3.40 → 3.80
        List<PriceLog> logs = priceLogMapper.selectList(new LambdaQueryWrapper<PriceLog>()
                .eq(PriceLog::getProductId, productA)
                .eq(PriceLog::getChangeSource, PurchaseReceiptListener.SOURCE_PURCHASE_PRICE));
        assertEquals(1, logs.size());
        assertEquals(0, logs.get(0).getOldPrice().compareTo(new BigDecimal("3.4000")));
        assertEquals(0, logs.get(0).getNewPrice().compareTo(new BigDecimal("3.8000")));
    }

    // ============ 用例6:比价接口 涨幅>20% 黄灯 ============

    @Test
    void priceCheckWarnOver20Pct() {
        Long doc1 = receiptService.createDirect(receiptReq(supplierId, null,
                rItem(productA, "24", null, "3.00", null)), OP);
        receiptService.confirm(doc1, OP);

        // 3.00 → 3.30(+10%):不警
        Map<String, Object> ok = receiptService.priceCheck(productA, supplierId, new BigDecimal("3.30"));
        assertEquals(false, ok.get("warn"));
        assertEquals(0, new BigDecimal(ok.get("diffPct").toString()).compareTo(new BigDecimal("10.0")));
        // 3.00 → 3.90(+30%):黄灯
        Map<String, Object> warn = receiptService.priceCheck(productA, supplierId, new BigDecimal("3.90"));
        assertEquals(true, warn.get("warn"));
        // 没买过的商品:无历史价不警
        Map<String, Object> none = receiptService.priceCheck(productB, supplierId, new BigDecimal("9.99"));
        assertEquals(false, none.get("warn"));
        assertNull(none.get("lastPrice"));
    }

    // ============ 用例7:超期黄灯 ============

    @Test
    void overdueYellowFlag() {
        Long poId = createPo(supplierId, LocalDate.now().minusDays(2), item(productA, "10", "3.40"));
        poService.place(poId, OP_NAME);
        PurchaseOrderService.PoVo vo = poService.detail(poId);
        assertTrue(vo.isOverdue(), "预计到货日已过且未收完 → 超期黄灯");
        // 在途明细行同样带 overdue 标记
        List<Map<String, Object>> lines = poService.inTransitLines(productA);
        assertEquals(1, lines.size());
        assertEquals(true, lines.get(0).get("overdue"));
    }

    // ============ 用例8:取消/关闭余量守卫 ============

    @Test
    void cancelAndCloseGuards() {
        // 已下单未收货可取消,取消后不计在途
        Long poId = createPo(supplierId, null, item(productA, "10", "3.40"));
        poService.place(poId, OP_NAME);
        poService.cancel(poId, OP_NAME);
        assertEquals(PurchaseOrder.ST_CANCELLED, poService.mustGet(poId).getPoStatus());
        assertEquals(0, poService.inTransit(productA).compareTo(BigDecimal.ZERO));

        // 部分到货不可取消 → 只能关闭余量,关闭后在途清零
        Long poId2 = createPo(supplierId, null, item(productB, "20", "0.80"));
        poService.place(poId2, OP_NAME);
        Long docId = receiptService.createDirect(receiptReq(supplierId, poId2,
                rItem(productB, "5", "20", "0.80", poItemId(poId2, productB))), OP);
        receiptService.confirm(docId, OP);
        assertThrows(BizException.class, () -> poService.cancel(poId2, OP_NAME));
        poService.closeRemaining(poId2, OP_NAME);
        assertEquals(PurchaseOrder.ST_DONE, poService.mustGet(poId2).getPoStatus());
        assertEquals(0, poService.inTransit(productB).compareTo(BigDecimal.ZERO));
    }

    // ============================== 造数工具 ==============================

    private Long createProduct(String name) {
        Product p = new Product();
        p.setSkuCode("TSTP-" + IdUtil.getSnowflakeNextIdStr());
        p.setProductName("M1-4测试·" + name);
        productMapper.insert(p);
        return p.getId();
    }

    private Long createSupplier(String name) {
        Supplier s = new Supplier();
        s.setSupplierCode("TSTS-" + IdUtil.getSnowflakeNextIdStr());
        s.setSupplierName("M1-4测试·" + name);
        supplierMapper.insert(s);
        return s.getId();
    }

    private PoCreateReq.Item item(Long productId, String qty, String price) {
        PoCreateReq.Item i = new PoCreateReq.Item();
        i.setProductId(productId);
        i.setQtyOrdered(new BigDecimal(qty));
        i.setUnitPrice(new BigDecimal(price));
        return i;
    }

    private Long createPo(Long supplierId, LocalDate expectDate, PoCreateReq.Item... items) {
        PoCreateReq req = new PoCreateReq();
        req.setSupplierId(supplierId);
        req.setExpectDate(expectDate);
        req.setItems(Arrays.asList(items));
        return poService.create(req, OP_NAME);
    }

    private ReceiptCreateReq receiptReq(Long supplierId, Long poId, ReceiptCreateReq.Item... items) {
        ReceiptCreateReq req = new ReceiptCreateReq();
        req.setSupplierId(supplierId);
        req.setBizDate(LocalDate.now());
        req.setPurchaseOrderId(poId);
        req.setItems(Arrays.asList(items));
        return req;
    }

    private ReceiptCreateReq.Item rItem(Long productId, String qty, String expectQty,
                                        String price, Long poItemId) {
        ReceiptCreateReq.Item i = new ReceiptCreateReq.Item();
        i.setProductId(productId);
        i.setQty(new BigDecimal(qty));
        i.setExpectQty(expectQty == null ? null : new BigDecimal(expectQty));
        i.setUnitPrice(new BigDecimal(price));
        i.setPoItemId(poItemId);
        return i;
    }

    private Long poItemId(Long poId, Long productId) {
        return poItemMapper.selectList(new LambdaQueryWrapper<PurchaseOrderItem>()
                        .eq(PurchaseOrderItem::getPoId, poId)
                        .eq(PurchaseOrderItem::getProductId, productId)).stream()
                .findFirst().map(PurchaseOrderItem::getId)
                .orElseThrow(() -> new IllegalStateException("po item not found"));
    }
}
