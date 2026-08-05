package top.aole.vend.modules.purchase.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrder;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import top.aole.vend.modules.purchase.mapper.PurchasePriceMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购收货入库服务:全部走 DocService(采购入库单一张单两状态承载"收货单→入库单",§9.2)。
 * - 从订货单一键生成:明细带 expect_qty 应收列(=订购−已收),实收可改;
 * - 无订单直接录入:兜底通道(现状小生意常态),expect_qty 为空不标差异;
 * - 确认入库 = DocService.confirm → 库存自动过账(stock 模块监听)+ 订货单回写(PurchaseReceiptListener);
 * - 重复确认由 DocStateMachine 天然拒绝(已确认→已确认非法流转),幂等。
 * - 比价:全部从已落账采购 doc_item 聚合,涨幅>20% 黄灯(阈值集中在 RISE_WARN_PCT)。
 */
@Service
@RequiredArgsConstructor
public class PurchaseReceiptService {

    /** 比价黄灯阈值:较最近一次进价涨幅超过 20% 提醒 */
    public static final BigDecimal RISE_WARN_PCT = new BigDecimal("20");

    private final DocService docService;
    private final PurchaseOrderService poService;
    private final PurchasePriceMapper priceMapper;

    // ============================== 收货单生成 ==============================

    /**
     * 从订货单一键生成采购入库草稿:只带未收完的行,应收=订购−已收,实收默认=应收(可改)。
     * @return docId
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromPo(Long poId, LocalDate bizDate, Long userId) {
        PurchaseOrder po = poService.mustGet(poId);
        if (!PurchaseOrder.ST_PLACED.equals(po.getPoStatus()) && !PurchaseOrder.ST_PARTIAL.equals(po.getPoStatus())) {
            throw new BizException(String.format("订货单[%s]状态[%s]不可收货(需为 已下单/部分到货)",
                    po.getPoNo(), po.getPoStatus()));
        }
        List<DocItemReq> items = new ArrayList<>();
        for (PurchaseOrderItem poItem : poService.itemsOf(poId)) {
            BigDecimal remaining = poItem.getQtyOrdered().subtract(poItem.getQtyReceived());
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            DocItemReq item = new DocItemReq();
            item.setProductId(poItem.getProductId());
            item.setQty(remaining);           // 实收默认=应收,录入时可改
            item.setExpectQty(remaining);     // 应收列(P0-5)
            item.setUnitPrice(poItem.getUnitPrice());
            item.setPoItemId(poItem.getId());
            items.add(item);
        }
        if (items.isEmpty()) {
            throw new BizException(String.format("订货单[%s]已全部收完,无可收行", po.getPoNo()));
        }
        DocCreateReq req = new DocCreateReq();
        req.setDocType(DocType.PURCHASE_IN);
        req.setBizDate(bizDate == null ? LocalDate.now() : bizDate);
        req.setSupplierId(po.getSupplierId());
        req.setPurchaseOrderId(poId);
        req.setDocSource(DocService.SOURCE_MANUAL);
        req.setRemark("从订货单 " + po.getPoNo() + " 生成");
        req.setItems(items);
        return docService.createDoc(req, userId);
    }

    /** 无订单直接录采购入库(兜底);也承接前端改完实收后的整单保存 */
    @Transactional(rollbackFor = Exception.class)
    public Long createDirect(ReceiptCreateReq req, Long userId) {
        return docService.createDoc(toDocReq(req), userId);
    }

    /** 改草稿(仅草稿态,DocService 把关) */
    @Transactional(rollbackFor = Exception.class)
    public void updateDraft(Long docId, ReceiptCreateReq req, Long userId) {
        DocHead head = docService.getDoc(docId).getHead();
        if (head.getDocType() != DocType.PURCHASE_IN) {
            throw new BizException("单据[" + head.getDocNo() + "]不是采购入库单");
        }
        docService.updateDraft(docId, toDocReq(req), userId);
    }

    /**
     * 确认入库:草稿先自动提交,再确认。
     * 确认动作触发(同事务):库存过账(stock)+ qty_received 回写与订单状态更新(listener)+ 进价 price_log。
     * 重复确认 → DocStateMachine 拒绝(幂等)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long docId, Long userId) {
        DocHead head = docService.getDoc(docId).getHead();
        if (head.getDocType() != DocType.PURCHASE_IN) {
            throw new BizException("单据[" + head.getDocNo() + "]不是采购入库单");
        }
        if (head.getDocStatus() == DocStatus.DRAFT) {
            docService.submit(docId, userId);
        }
        docService.confirm(docId, userId, false);
    }

    // ============================== 查询 ==============================

    /** 采购入库单列表(带供应商名/品项数/差异行数) */
    public List<Map<String, Object>> receiptList() {
        return priceMapper.receiptList();
    }

    /** 采购入库单详情:头 + 明细(应收/实收两列,带差异) */
    public Map<String, Object> receiptDetail(Long docId) {
        DocService.DocDetail doc = docService.getDoc(docId);
        if (doc.getHead().getDocType() != DocType.PURCHASE_IN) {
            throw new BizException("单据[" + doc.getHead().getDocNo() + "]不是采购入库单");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("head", doc.getHead());
        result.put("items", priceMapper.receiptItems(docId));
        return result;
    }

    // ============================== 采购价历史 / 比价 ==============================

    /** 价格本:每商品×供应商 最近价/最低价/次数(从已落账采购史聚合) */
    public List<Map<String, Object>> priceHistory(Long productId, Long supplierId) {
        return priceMapper.priceHistory(productId, supplierId);
    }

    /**
     * 录单比价:当前录入价 vs 同品历史价。
     * 优先比同供应商最近价,该供应商没买过则退回全供应商最近价;涨幅>20% → warn 黄灯。
     */
    public Map<String, Object> priceCheck(Long productId, Long supplierId, BigDecimal price) {
        BigDecimal lastPrice = priceMapper.lastPrice(productId, supplierId, null);
        String compareScope = "同供应商";
        if (lastPrice == null && supplierId != null) {
            lastPrice = priceMapper.lastPrice(productId, null, null);
            compareScope = "全部供应商";
        }
        Map<String, Object> result = new HashMap<>();
        result.put("lastPrice", lastPrice);
        result.put("compareScope", compareScope);
        result.put("history", priceMapper.priceHistory(productId, null));
        if (lastPrice == null || price == null || lastPrice.compareTo(BigDecimal.ZERO) == 0) {
            result.put("diffPct", null);
            result.put("warn", false);
            return result;
        }
        BigDecimal diffPct = price.subtract(lastPrice)
                .multiply(new BigDecimal("100"))
                .divide(lastPrice, 1, RoundingMode.HALF_UP);
        result.put("diffPct", diffPct);
        result.put("warn", diffPct.compareTo(RISE_WARN_PCT) > 0);
        return result;
    }

    // ============================== 内部 ==============================

    private DocCreateReq toDocReq(ReceiptCreateReq req) {
        DocCreateReq docReq = new DocCreateReq();
        docReq.setDocType(DocType.PURCHASE_IN);
        docReq.setBizDate(req.getBizDate());
        docReq.setSupplierId(req.getSupplierId());
        docReq.setPurchaseOrderId(req.getPurchaseOrderId());
        docReq.setDocSource(DocService.SOURCE_MANUAL);
        docReq.setRemark(req.getRemark());
        docReq.setItems(req.getItems().stream().map(i -> {
            DocItemReq item = new DocItemReq();
            item.setProductId(i.getProductId());
            item.setQty(i.getQty());
            item.setExpectQty(i.getExpectQty());
            item.setUnitPrice(i.getUnitPrice());
            item.setPoItemId(i.getPoItemId());
            item.setRemark(i.getRemark());
            return item;
        }).collect(Collectors.toList()));
        return docReq;
    }
}
