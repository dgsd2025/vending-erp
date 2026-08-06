package top.aole.vend.modules.money.domain.event;

import lombok.Getter;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * "钱账过账"领域事件:cash_flow 唯一写手(CashFlowWriter,包私有)的唯一触发源。
 *
 * === M3 后续票的对接契约(M3-2 付款 / M3-4 平台结算 / M3-5 资金调整 / M3-6 索赔)===
 * 单据确认后(同事务)发布本事件即可落流水,示例:
 * <pre>
 *   MoneyPostingEvent event = new MoneyPostingEvent("付款单", payment.getId(), operatorId);
 *   event.outflow(bankAccountId, amount, CashFlowCategory.SUPPLIER_PAYMENT, payTime, "付XX货款");
 *   applicationEventPublisher.publishEvent(event);   // 同步同事务,失败=确认整体回滚
 * </pre>
 * 规矩(写手强制校验,违者 BizException 回滚):
 * ① refDocType/refDocId 必填——钱只能被单据改(§9.1);
 * ② 每行 direction 收/支、amount>0、category 必填;pl_line 由 category 唯一推导,不许自报;
 * ③ 虚拟账户(平台待结算/老板垫付)不可手工收支(category=资金调整 直接拒绝);
 * ④ book_period 写手按锁账口径推导(业务月已锁 → 记当月),发布方不用管。
 */
@Getter
public class MoneyPostingEvent {

    /** 来源单据类型:付款单/平台结算单/支出单/索赔单/资金调整单/线下复合单… */
    private final String refDocType;
    /** 来源单据 ID */
    private final Long refDocId;
    /** 操作人(落 cash_flow.create_user) */
    private final Long operator;
    private final List<Line> lines = new ArrayList<>();

    public MoneyPostingEvent(String refDocType, Long refDocId, Long operator) {
        this.refDocType = refDocType;
        this.refDocId = refDocId;
        this.operator = operator;
    }

    /** 加一条收入行 */
    public MoneyPostingEvent inflow(Long accountId, BigDecimal amount, CashFlowCategory category,
                                    LocalDateTime bizTime, String remark) {
        lines.add(new Line(accountId, top.aole.vend.modules.money.domain.entity.CashFlow.DIR_IN,
                amount, category, bizTime, remark));
        return this;
    }

    /** 加一条支出行 */
    public MoneyPostingEvent outflow(Long accountId, BigDecimal amount, CashFlowCategory category,
                                     LocalDateTime bizTime, String remark) {
        lines.add(new Line(accountId, top.aole.vend.modules.money.domain.entity.CashFlow.DIR_OUT,
                amount, category, bizTime, remark));
        return this;
    }

    /** 一条流水行(方向+金额+类别;pl_line 由类别推导) */
    @Getter
    public static class Line {
        private final Long accountId;
        private final String direction;
        private final BigDecimal amount;
        private final CashFlowCategory category;
        private final LocalDateTime bizTime;
        private final String remark;

        Line(Long accountId, String direction, BigDecimal amount,
             CashFlowCategory category, LocalDateTime bizTime, String remark) {
            this.accountId = accountId;
            this.direction = direction;
            this.amount = amount;
            this.category = category;
            this.bizTime = bizTime;
            this.remark = remark;
        }
    }
}
