package top.aole.vend.modules.purchase.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.purchase.service.PurchaseReceiptService;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购模块接口(M1-4):轻量订货单(P0-5)+ 收货入库(doc 承载)+ 在途查询 + 采购价历史/比价。
 * 经手人:X-User-Name 占位(SSO 后替换);单据侧 user_id 暂 0L 占位,与 DocService 约定一致。
 */
@Api(tags = "采购 · 订货/收货入库/在途/比价")
@RestController
@RequestMapping("/v1/purchase")
@RequiredArgsConstructor
public class PurchaseController {

    /** SSO 接入前的 user_id 占位(op_log 里以 X-User-Name 文本留痕) */
    private static final Long UID = 0L;

    private final PurchaseOrderService poService;
    private final PurchaseReceiptService receiptService;

    // ============================== 订货单 ==============================

    @ApiOperation("订货单分页(带在途/超期黄灯)")
    @GetMapping("/orders")
    public R<Page<PurchaseOrderService.PoVo>> pageOrders(@RequestParam(defaultValue = "1") long current,
                                                         @RequestParam(defaultValue = "20") long size,
                                                         @RequestParam(required = false) String poStatus,
                                                         @RequestParam(required = false) Long supplierId) {
        return R.ok(poService.page(current, size, poStatus, supplierId));
    }

    @ApiOperation("订货单详情(头+汇总)")
    @GetMapping("/orders/{id}")
    public R<PurchaseOrderService.PoVo> orderDetail(@PathVariable Long id) {
        return R.ok(poService.detail(id));
    }

    @ApiOperation("订货单明细行(带商品名/箱规/已收)")
    @GetMapping("/orders/{id}/items")
    public R<List<Map<String, Object>>> orderItems(@PathVariable Long id) {
        return R.ok(poService.itemsWithProduct(id));
    }

    @ApiOperation("新建订货单(草稿)")
    @PostMapping("/orders")
    public R<Long> createOrder(@Valid @RequestBody PoCreateReq req,
                               @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(poService.create(req, Operators.resolve(userName)));
    }

    @ApiOperation("修改订货单草稿")
    @PutMapping("/orders/{id}")
    public R<Void> updateOrder(@PathVariable Long id, @Valid @RequestBody PoCreateReq req,
                               @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        poService.updateDraft(id, req, Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("下单:草稿→已下单(开始计在途)")
    @PostMapping("/orders/{id}/place")
    public R<Void> placeOrder(@PathVariable Long id,
                              @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        poService.place(id, Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("取消订货单(仅草稿/已下单且未收过货)")
    @PostMapping("/orders/{id}/cancel")
    public R<Void> cancelOrder(@PathVariable Long id,
                               @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        poService.cancel(id, Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("关闭余量:部分到货→已完成(不再等剩余货)")
    @PostMapping("/orders/{id}/close")
    public R<Void> closeOrder(@PathVariable Long id,
                              @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        poService.closeRemaining(id, Operators.resolve(userName));
        return R.ok();
    }

    // ============================== 在途查询(M2 补货公式取数口) ==============================

    @ApiOperation("全 SKU 在途汇总(>0)")
    @GetMapping("/in-transit")
    public R<List<Map<String, Object>>> inTransitAll() {
        return R.ok(poService.inTransitAll());
    }

    @ApiOperation("单 SKU 在途:合计+按订货单明细(带超期黄灯)")
    @GetMapping("/in-transit/{productId}")
    public R<Map<String, Object>> inTransit(@PathVariable Long productId) {
        Map<String, Object> result = new HashMap<>();
        result.put("productId", productId);
        result.put("inTransitQty", poService.inTransit(productId));
        result.put("lines", poService.inTransitLines(productId));
        return R.ok(result);
    }

    // ============================== 收货入库(doc 承载) ==============================

    @ApiOperation("从订货单一键生成采购入库草稿(应收列带出,实收可改)")
    @PostMapping("/orders/{id}/receipts")
    public R<Map<String, Object>> createReceiptFromPo(@PathVariable Long id,
                                                      @RequestParam(required = false)
                                                      @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                                      LocalDate bizDate) {
        Long docId = receiptService.createFromPo(id, bizDate, UID);
        return R.ok(receiptService.receiptDetail(docId));
    }

    @ApiOperation("无订单直接录采购入库(兜底)/保存整单")
    @PostMapping("/receipts")
    public R<Map<String, Object>> createReceipt(@Valid @RequestBody ReceiptCreateReq req) {
        Long docId = receiptService.createDirect(req, UID);
        return R.ok(receiptService.receiptDetail(docId));
    }

    @ApiOperation("修改采购入库草稿(实收/单价可改)")
    @PutMapping("/receipts/{docId}")
    public R<Map<String, Object>> updateReceipt(@PathVariable Long docId, @Valid @RequestBody ReceiptCreateReq req) {
        receiptService.updateDraft(docId, req, UID);
        return R.ok(receiptService.receiptDetail(docId));
    }

    @ApiOperation("确认入库:自动过账库存+回写订货单已收量;重复确认被状态机拒绝")
    @PostMapping("/receipts/{docId}/confirm")
    public R<Map<String, Object>> confirmReceipt(@PathVariable Long docId) {
        receiptService.confirm(docId, UID);
        return R.ok(receiptService.receiptDetail(docId));
    }

    @ApiOperation("采购入库单列表(带供应商/差异行数)")
    @GetMapping("/receipts")
    public R<List<Map<String, Object>>> receiptList() {
        return R.ok(receiptService.receiptList());
    }

    @ApiOperation("采购入库单详情(应收/实收两列)")
    @GetMapping("/receipts/{docId}")
    public R<Map<String, Object>> receiptDetail(@PathVariable Long docId) {
        return R.ok(receiptService.receiptDetail(docId));
    }

    // ============================== 价格本 / 比价 ==============================

    @ApiOperation("采购价历史:每商品×供应商 最近价/最低价/次数")
    @GetMapping("/price-history")
    public R<List<Map<String, Object>>> priceHistory(@RequestParam Long productId,
                                                     @RequestParam(required = false) Long supplierId) {
        return R.ok(receiptService.priceHistory(productId, supplierId));
    }

    @ApiOperation("录单比价:当前价 vs 历史价,涨幅>20% 黄灯")
    @GetMapping("/price-check")
    public R<Map<String, Object>> priceCheck(@RequestParam Long productId,
                                             @RequestParam(required = false) Long supplierId,
                                             @RequestParam(required = false) BigDecimal price) {
        return R.ok(receiptService.priceCheck(productId, supplierId, price));
    }
}
