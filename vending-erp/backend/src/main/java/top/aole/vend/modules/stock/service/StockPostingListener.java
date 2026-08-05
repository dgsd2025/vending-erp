package top.aole.vend.modules.stock.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.domain.event.DocConfirmedEvent;

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

    @EventListener
    public void onDocConfirmed(DocConfirmedEvent event) {
        DocHead head = event.getHead();
        DocType type = head.getDocType();

        if (type.isPostingDeferred()) {
            // TODO(M1-7):红冲=按 red_flush_of 反向冲原单流水(免负库存拦截);
            //   成本调整=未售部分调 stock_ledger.unit_cost、已售进利润表"成本调整"行;
            //   资金调整(M3)=生成 cash_flow,不动库存。本票只留通道。
            log.info("单据[{}]类型[{}]过账逻辑延后实现(M1-7/M3),本次跳过", head.getDocNo(), type.getLabel());
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
