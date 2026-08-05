package top.aole.vend.modules.purchase.service;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.domain.entity.PriceLog;
import top.aole.vend.modules.basedata.infrastructure.mapper.PriceLogMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.domain.event.DocConfirmedEvent;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem;
import top.aole.vend.modules.purchase.mapper.PurchaseOrderItemMapper;
import top.aole.vend.modules.purchase.mapper.PurchasePriceMapper;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * 采购入库确认监听器:qty_received 的唯一写手。
 *
 * 与库存过账(StockPostingListener)同吃 DocConfirmedEvent,同步同事务——
 * 回写失败则确认整体回滚。重复确认被 DocStateMachine 拦住,事件天然只发一次(幂等)。
 *
 * 干两件事:
 * 1. P0-5 回写:明细带 po_item_id 的行 → qty_received += 实收,再重算订单状态
 *    (全收=已完成 / 部分=部分到货);在途=Σ(订购−已收)随之自动缩小。
 * 2. 进价留痕:实际进价 ≠ 该商品最近一次落账进价 → 自动写 price_log(change_source=进价),
 *    喂"价格本/比价提示/定价 PDCA"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseReceiptListener {

    /** price_log.change_source 进价专用值(与 手工/导入侦测 区分) */
    public static final String SOURCE_PURCHASE_PRICE = "进价";

    private final PurchaseOrderItemMapper poItemMapper;
    private final PurchaseOrderService poService;
    private final PurchasePriceMapper priceMapper;
    private final PriceLogMapper priceLogMapper;
    private final OpLogService opLogService;

    @EventListener
    public void onDocConfirmed(DocConfirmedEvent event) {
        DocHead head = event.getHead();
        // M1-7 红冲连锁:红冲采购入库单 → 回冲订货单已收量(qty_received −=,在途恢复)。
        // 红冲单明细 1:1 复制原单(含 po_item_id),这里按同一批 po_item_id 反向回写。
        if (head.getDocType() == DocType.RED_FLUSH) {
            reversePoOnRedFlush(head, event);
            return;
        }
        if (head.getDocType() != DocType.PURCHASE_IN) {
            return;
        }
        writeBackPo(head, event);
        logPriceChanges(head, event);
    }

    /** 红冲回冲:qty_received −= 红冲量(不低于0),重算订单状态;明细无 po_item_id 则无事发生 */
    private void reversePoOnRedFlush(DocHead head, DocConfirmedEvent event) {
        Set<Long> touchedPoIds = new HashSet<>();
        for (DocItem item : event.getItems()) {
            if (item.getPoItemId() == null) {
                continue;
            }
            PurchaseOrderItem poItem = poItemMapper.selectById(item.getPoItemId());
            if (poItem == null) {
                throw new BizException(String.format(
                        "红冲单[%s]明细关联的订货明细不存在:po_item_id=%d", head.getDocNo(), item.getPoItemId()));
            }
            String before = JSONUtil.toJsonStr(poItem);
            BigDecimal after = poItem.getQtyReceived().subtract(item.getQty());
            poItem.setQtyReceived(after.max(BigDecimal.ZERO));
            poItem.setUpdateUser(head.getConfirmBy());
            poItemMapper.updateById(poItem);
            opLogService.recordJson(head.getConfirmBy(), "红冲回冲已收量", "purchase_order_item",
                    poItem.getId(), before, JSONUtil.toJsonStr(poItem));
            touchedPoIds.add(poItem.getPoId());
        }
        for (Long poId : touchedPoIds) {
            poService.reopenAfterRedFlush(poId, head.getConfirmBy());
        }
    }

    /** P0-5:回写 qty_received + 重算订单状态 */
    private void writeBackPo(DocHead head, DocConfirmedEvent event) {
        Set<Long> touchedPoIds = new HashSet<>();
        for (DocItem item : event.getItems()) {
            if (item.getPoItemId() == null) {
                continue;
            }
            PurchaseOrderItem poItem = poItemMapper.selectById(item.getPoItemId());
            if (poItem == null) {
                throw new BizException(String.format(
                        "采购入库单[%s]明细关联的订货明细不存在:po_item_id=%d", head.getDocNo(), item.getPoItemId()));
            }
            if (!poItem.getProductId().equals(item.getProductId())) {
                throw new BizException(String.format(
                        "采购入库单[%s]商品[%d]与订货明细[%d]的商品[%d]不一致,拒绝回写",
                        head.getDocNo(), item.getProductId(), poItem.getId(), poItem.getProductId()));
            }
            String before = JSONUtil.toJsonStr(poItem);
            poItem.setQtyReceived(poItem.getQtyReceived().add(item.getQty()));
            poItem.setUpdateUser(head.getConfirmBy());
            poItemMapper.updateById(poItem);
            opLogService.recordJson(head.getConfirmBy(), "收货回写已收量", "purchase_order_item",
                    poItem.getId(), before, JSONUtil.toJsonStr(poItem));
            touchedPoIds.add(poItem.getPoId());
        }
        for (Long poId : touchedPoIds) {
            poService.recomputeStatus(poId, head.getConfirmBy());
        }
    }

    /** 进价变动 → price_log(change_source=进价);首笔采购无历史价不记 */
    private void logPriceChanges(DocHead head, DocConfirmedEvent event) {
        for (DocItem item : event.getItems()) {
            BigDecimal newPrice = item.getUnitPrice();
            if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // 无价行不参与价格史(禁 0 参与,§13 场景7同源原则)
            }
            BigDecimal lastPrice = priceMapper.lastPrice(item.getProductId(), null, head.getId());
            if (lastPrice == null || lastPrice.compareTo(newPrice) == 0) {
                continue;
            }
            PriceLog priceLog = new PriceLog();
            priceLog.setProductId(item.getProductId());
            priceLog.setOldPrice(lastPrice);
            priceLog.setNewPrice(newPrice);
            priceLog.setChangeSource(SOURCE_PURCHASE_PRICE);
            priceLog.setEffectDate(head.getBizDate());
            priceLog.setConfirmBy(head.getConfirmBy());
            priceLog.setCreateUser(head.getConfirmBy());
            priceLogMapper.insert(priceLog);
            log.info("进价变动留痕:商品[{}] {} → {}(单据[{}])",
                    item.getProductId(), lastPrice, newPrice, head.getDocNo());
        }
    }
}
