package top.aole.vend.modules.settle.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 付款单(yc_vend_payment):三要素 付给谁/从哪个账户/多少钱(§7.3 问2)。
 *
 * 凭证硬门禁(§9.1-4):无转账截图(attachment refType=payment)不能确认付款;
 * 确认 = 发布 MoneyPostingEvent(SUPPLIER_PAYMENT)落 cash_flow(钱只能被单据改)。
 * 可选核销结算单(不强制逐单);金额=结算单实结 → 自动核销全链闭环(采购单→已结算);
 * 金额≠实结 → 双双差异挂起,补说明(resolve-diff)或改单后闭环。预付=供应商余额为负。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_payment")
public class Payment extends BaseEntity {

    public static final String ST_PENDING = "待付款";
    /** 确认中(M3-9 P0-1 条件更新抢占的过渡态:仅存在于确认事务内,成功即落终态,失败随事务回滚) */
    public static final String ST_CONFIRMING = "确认中";
    public static final String ST_PAID = "已付款";
    public static final String ST_SETTLED = "结算完成";
    public static final String ST_DIFF = "差异挂起";
    /** 已作废(M3-9 七律修复:仅待付款可作废——钱没动,单据留痕不删) */
    public static final String ST_VOID = "已作废";
    /** 已红冲(钱已动的唯一逆向:被负额红冲行承接,历史不改写) */
    public static final String ST_RED_FLUSHED = "已红冲";
    /** 红冲行自身状态(负额,pay_time 非空 → Σ已付款自动回落) */
    public static final String ST_RED = "红冲";

    private String payNo;

    private Long supplierId;

    /** 从哪个账户付(真实账户;虚拟账户拒绝) */
    private Long accountId;

    /** 实付金额(如 1911.37 = 2254 − 351.63 抵扣) */
    private BigDecimal amount;

    /** 兑换/补贴抵扣额(展示冗余:核销结算单时带过来,对应"厂家已结账217.3") */
    private BigDecimal deductionAmount;

    /** 关联应付结算单(可选,不强制逐单核销) */
    private Long settleBillId;

    private String payStatus;

    /** 付款确认人(单人现实:软约束,与录单人可同) */
    private Long confirmBy;

    private LocalDateTime confirmAt;

    /** 付款时间(非空=钱已动,应付余额 Σ付款 以此为准) */
    private LocalDateTime payTime;

    private String bookPeriod;

    private String remark;

    /** 红冲指向的原付款单ID(本行=负额红冲行;原单被红冲后 pay_status=已红冲) */
    private Long redFlushOf;
}
