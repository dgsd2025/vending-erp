package top.aole.vend.modules.settle.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.domain.event.DocConfirmedEvent;
import top.aole.vend.modules.settle.domain.entity.SettleBill;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 应付结算单唯一生产者(§9.2):采购入库单确认事件 → 自动生成 settle_bill(待确认)。
 *
 * 同步同事务(确认失败整体回滚);无供应商的采购单(如测试垫底单/期初)不产生应付。
 * 到期日:月结 = 入库日 + 账期天数(逾期黄灯);现结/预付 = 入库当日。
 * 单据状态:采购单确认后仍停"已确认"——老板复核结算单(待付款)时才进"待结算",
 * 保证未启用应付流程的旧链路(红冲/完成)不被挡路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettleBillGenerator {

    private final SettleBillService settleBillService;
    private final SupplierMapper supplierMapper;

    @EventListener
    public void onDocConfirmed(DocConfirmedEvent event) {
        DocHead head = event.getHead();
        if (head.getDocType() != DocType.PURCHASE_IN
                || event.getPhase() != DocConfirmedEvent.PostingPhase.FULL
                || head.getSupplierId() == null) {
            return;
        }
        BigDecimal amount = head.getTotalAmount() == null ? BigDecimal.ZERO : head.getTotalAmount();
        LocalDate dueDate = dueDateOf(head.getSupplierId(), head.getBizDate());
        SettleBill bill = settleBillService.createFromPurchase(head, amount, dueDate);
        log.info("应付结算单自动生成:采购入库[{}] → 结算单[{}] 应结 {}", head.getDocNo(), bill.getBillNo(), amount);
    }

    private LocalDate dueDateOf(Long supplierId, LocalDate bizDate) {
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier != null && "月结".equals(supplier.getSettleMethod())
                && supplier.getAccountDays() != null && supplier.getAccountDays() > 0) {
            return bizDate.plusDays(supplier.getAccountDays());
        }
        return bizDate;
    }
}
