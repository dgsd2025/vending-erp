package top.aole.vend.modules.settlement.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 平台结算单 / 商户账单核对单(yc_vend_settlement,附录D 双模式):
 *
 * - PLATFORM(平台归集):录入 区间/平台账单额/手续费/实际到账/入账账户 + 平台账单凭证必传 →
 *   确认 = 系统额快照(区间内待回填正常−退款聚合)+ 两差对账 + 回填 sale_record.settlement_id
 *   + MoneyPostingEvent 两笔流水(货款结算到账毛额 收 / 平台手续费 支);
 * - DIRECT(微信/支付宝直连):同一张表当"商户账单核对单"用——只录 区间/平台账单额,
 *   确认只算 系统销售额 vs 账单额 的漏单差,**不落流水不回填**(钱已直连入账,真实到账靠
 *   M3-5 钱盘账户核对对总,避免双记);
 * - mode_snap 录入时快照:确认时必须与当前 settle.mode 一致,改模式后旧单只能作废重录。
 *
 * 口径(P0-3/§13.2-3 写死):system_amount 只聚合 order_type=正常/退款(退款为负),
 * 兑换/测试/线下补录一律不进待结算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_settlement")
public class Settlement extends BaseEntity {

    // ---- 状态机(V1.0.11 口径) ----
    public static final String ST_PENDING = "待核对";
    /** PLATFORM 确认完成(钱+回填都落了) */
    public static final String ST_SETTLED = "已核销";
    /** DIRECT 确认完成(只出差异报告) */
    public static final String ST_CHECKED = "已核对";
    /** 两差任一超阈值(钱/回填照落,人工复核后 resolve 收口) */
    public static final String ST_DIFF = "差异挂起";
    public static final String ST_VOID = "已作废";
    /** 确认过程中的事务内瞬时态(并发抢占用,提交前必被终态覆盖) */
    public static final String ST_CONFIRMING = "核销中";

    /** 单号:PLATFORM=PT- 前缀 / DIRECT=HD-(核对)前缀 */
    private String stmtNo;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    /** 平台账单销售额(两种模式都录:PLATFORM=结算账单额,DIRECT=商户对账单额) */
    private BigDecimal platformAmount;

    /** 平台手续费(报表单列"平台这个月吃掉了我多少钱";DIRECT 不适用=0) */
    private BigDecimal feeAmount;

    /** 实际到账(DIRECT 不适用=0) */
    private BigDecimal actualAmount;

    /** 系统销售额快照(确认时聚合,口径=仅正常/退款,P0-3) */
    private BigDecimal systemAmount;

    /** 差异①漏单差 = 系统销售额 − 平台账单额(差=漏单/吞货) */
    private BigDecimal diffSales;

    /** 差异②扣款差 = 预计到账(账单−手续费) − 实际到账(差=费率变化/额外扣款;DIRECT 恒 0) */
    private BigDecimal diffArrival;

    /** 入账账户(PLATFORM 必填真实账户;DIRECT 不适用) */
    private Long accountId;

    private String stlStatus;

    /** 录入时结算模式快照(PLATFORM/DIRECT) */
    private String modeSnap;

    private String confirmBy;

    private LocalDateTime confirmAt;

    /** 差异挂起复核说明(resolve 留痕) */
    private String diffNote;

    private String bookPeriod;
}
