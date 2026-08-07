package top.aole.vend.modules.money.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;

/**
 * 钱账过账监听器:cash_flow 唯一写手(CashFlowWriter)的事件入口。
 *
 * 只接受 {@link MoneyPostingEvent}(单据确认事件)驱动写入,同步同事务——
 * 校验失败(方向/金额/类别/虚拟账户/无关联单据)异常上抛,单据确认整体回滚。
 * 对接方式见 MoneyPostingEvent 类注释(M3-2 付款 / M3-4 结算 / M3-5 调整 / M3-6 索赔)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MoneyPostingListener {

    private final CashFlowWriter writer;

    @EventListener
    public void onMoneyPosting(MoneyPostingEvent event) {
        int n = writer.post(event).size();
        log.info("钱账过账:单据[{}:{}] 落流水 {} 条", event.getRefDocType(), event.getRefDocId(), n);
    }
}
