package top.aole.vend.modules.stocktake.money.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * 资金调整单扩展(yc_vend_cash_adjust,M3-5 · 审计 P1-7)。
 *
 * 单据主体复用 doc 通道(doc_head.doc_type=资金调整,状态机/DocStatusGuard/op_log 全套);
 * 本表只放钱字段:账户/方向/金额/原因/来源核对记录。
 * 确认(老板角色)后经 MoneyPostingEvent → CashFlowWriter 落 cash_flow(虚拟拒绝真实放行)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_cash_adjust")
public class CashAdjust extends BaseEntity {

    public static final String DIR_IN = "收";
    public static final String DIR_OUT = "支";

    /** 关联资金调整单(doc_head) */
    private Long docId;
    /** 调整哪个账户(仅真实账户) */
    private Long accountId;
    /** 方向:收/支 */
    private String direction;
    /** 调整金额(恒正,方向看 direction) */
    private BigDecimal amount;
    /** 原因枚举中文值:盘盈/盘亏/手续费漏记/期初错/其他 */
    private String reason;
    /** 来源钱盘核对记录(核对差异行一键生成时回链) */
    private Long cashCheckId;
}
