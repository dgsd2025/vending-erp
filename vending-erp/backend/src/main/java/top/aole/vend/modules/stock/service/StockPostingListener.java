package top.aole.vend.modules.stock.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.domain.event.DocConfirmedEvent;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;

import java.math.BigDecimal;

/**
 * 库存过账监听器:stock_ledger 唯一写手的事件入口。
 *
 * 只接受"单据已确认"事件驱动写入(同步同事务,失败=确认整体回滚)。
 * 方向由 DocType 上的仓库/机器方向配置决定,不在这里写 if-else 口径。
 *
 * P0-4 预挂单:WAREHOUSE_ONLY 只锁仓库侧;转正时 MACHINE_ONLY 补机器侧。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockPostingListener {

    private final StockLedgerWriter writer;
    private final DocHeadMapper docHeadMapper;

    @EventListener
    public void onDocConfirmed(DocConfirmedEvent event) {
        DocHead head = event.getHead();
        DocType type = head.getDocType();

        if (type.isPostingDeferred()) {
            // TODO(M3):资金调整=确认后生成 cash_flow(钱盘差异唯一出口),不动库存。
            log.info("单据[{}]类型[{}]过账逻辑延后实现(M3),本次跳过", head.getDocNo(), type.getLabel());
            return;
        }

        // M1-7 红冲(P0-1):按原单 doc_type 的方向**取反**过账——采购入库红冲=仓库−,
        // 出库上架红冲=仓库+机器−。红冲单 neg_stock_exempt=1(免负库存拦截),
        // 明细/机器与原单 1:1 复制(RedFlushService 保证),此处只管方向取反。
        if (type == DocType.RED_FLUSH) {
            DocHead origin = docHeadMapper.selectById(head.getRedFlushOf());
            if (origin == null) {
                throw new BizException("红冲单[" + head.getDocNo() + "]找不到原单据:" + head.getRedFlushOf());
            }
            DocType originType = origin.getDocType();
            for (DocItem item : event.getItems()) {
                if (originType.getWarehouseDirection() != 0) {
                    writer.postWarehouse(head, item,
                            signed(item.getQty(), -originType.getWarehouseDirection()), event.getBizTime());
                }
                if (originType.getMachineDirection() != 0) {
                    writer.postMachine(head, item,
                            signed(item.getQty(), -originType.getMachineDirection()), event.getBizTime());
                }
            }
            return;
        }

        // M1-7 成本调整(附录C 过账契约):未售部分 → qty=0/amount=Δ 的仓库账调整流水;
        // 已售部分不追溯,金额在单据备注 + pl_line='成本调整'(M3 利润表行承接),不落 ledger。
        if (type == DocType.COST_ADJUST) {
            for (DocItem item : event.getItems()) {
                if (item.getAmount() != null && item.getAmount().compareTo(BigDecimal.ZERO) != 0) {
                    writer.postCostAdjust(head, item, item.getAmount(), event.getBizTime());
                }
            }
            return;
        }

        for (DocItem item : event.getItems()) {
            if (type.getWarehouseDirection() != 0
                    && event.getPhase() != DocConfirmedEvent.PostingPhase.MACHINE_ONLY) {
                writer.postWarehouse(head, item,
                        signed(item.getQty(), type.getWarehouseDirection()), event.getBizTime());
            }
            if (type.getMachineDirection() != 0
                    && event.getPhase() != DocConfirmedEvent.PostingPhase.WAREHOUSE_ONLY) {
                writer.postMachine(head, item,
                        signed(item.getQty(), type.getMachineDirection()), event.getBizTime());
            }
        }
    }

    private static BigDecimal signed(BigDecimal qty, int direction) {
        return direction >= 0 ? qty : qty.negate();
    }
}
