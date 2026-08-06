package top.aole.vend.modules.money.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金流水(yc_vend_cash_flow):**全系统唯一钱账**。
 *
 * 铁律(§9.1 / 附录D):钱不允许"直接记一笔",任何流水必须由单据生成
 * (ref_doc_type/ref_doc_id 必填);唯一写手 = money 包内 CashFlowWriter(包私有),
 * 只接受 MoneyPostingEvent(单据确认事件);流水不许删,错账走资金调整/红冲。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_cash_flow")
public class CashFlow extends BaseEntity {

    public static final String DIR_IN = "收";
    public static final String DIR_OUT = "支";

    /** 流水号(uk) */
    private String flowNo;

    /** 账户 */
    private Long accountId;

    /** 方向:收/支 */
    private String direction;

    /** 金额(恒正,方向看 direction) */
    private BigDecimal amount;

    /** 业务类别中文值(CashFlowCategory.label) */
    private String category;

    /** 利润表行映射(PlLine.label):由类别唯一推导,写入方不许自报 */
    private String plLine;

    /** 来源单据类型:付款单/平台结算单/支出单/索赔单/资金调整单/线下复合单… */
    private String refDocType;

    /** 来源单据 ID(必填,钱只能被单据改) */
    private Long refDocId;

    /** 业务时间 */
    private LocalDateTime bizTime;

    /** 入账月 yyyy-MM(锁账口径同 sale_record:业务月已锁 → 记当月) */
    private String bookPeriod;

    /** 摘要 */
    private String remark;
}
