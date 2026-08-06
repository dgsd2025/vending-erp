package top.aole.vend.modules.settle.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 应付结算单(yc_vend_settle_bill):业财一体链条中枢(§9.2)。
 *
 * 唯一生产者 = 采购入库单确认事件(SettleBillGenerator)——不存在手工"直接记一笔应付"。
 * 正常单状态:待确认 →(老板复核,带入同供应商待抵扣)→ 待付款 →(付款+凭证)→ 已付款 → 已完成;
 *            凭证金额≠单金额 → 差异挂起(补说明或改单);红冲未付款单 → 已作废(抵扣释放)。
 * 红字单(direction=红字,§13.2-1):红冲已付款采购单生成,状态 待冲抵 → 已冲抵(冲进下一张正常单,
 *            offset_into_bill_id 回写),不动已完成付款。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_settle_bill")
public class SettleBill extends BaseEntity {

    // ---- 方向 ----
    public static final String DIR_NORMAL = "正常";
    public static final String DIR_RED = "红字";

    // ---- 正常单状态 ----
    public static final String ST_PENDING_CONFIRM = "待确认";
    public static final String ST_PENDING_PAY = "待付款";
    public static final String ST_PAID = "已付款";
    public static final String ST_DONE = "已完成";
    public static final String ST_DIFF = "差异挂起";
    public static final String ST_VOID = "已作废";

    // ---- 红字单状态 ----
    public static final String ST_RED_PENDING = "待冲抵";
    public static final String ST_RED_USED = "已冲抵";

    private String billNo;

    /** 应付/应收(本票只产应付) */
    private String billType;

    /** 正常/红字(P0-1 红冲连锁) */
    private String direction;

    private Long supplierId;

    /** 来源单据:正常单=采购入库单;红字单=红冲单 */
    private Long sourceDocId;

    /** 应结金额(=实收入库金额) */
    private BigDecimal amountDue;

    /** 抵扣明细合计(仅允许同供应商待抵扣,P2-11 防串户) */
    private BigDecimal deductionAmount;

    /** 实结金额 = 应结 − 抵扣 − 红字冲抵 */
    private BigDecimal amountActual;

    private String billStatus;

    /** 锁后新增差异提示(P0-2,已核销单遇锁账补导只展示不改状态) */
    private String lockDiffNote;

    /** 差异处理说明(凭证金额≠结算单金额→挂起,补说明后闭环留痕) */
    private String diffNote;

    /** 红字专用:被冲抵进哪张正常结算单 */
    private Long offsetIntoBillId;

    /** 到期日(月结=入库日+账期天数;现结=入库当日;逾期黄灯取数) */
    private LocalDate dueDate;

    private String bookPeriod;
}
