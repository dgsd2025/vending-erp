package top.aole.vend.modules.settle.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * 抵扣确认单(yc_vend_deduction):兑换/厂家补贴形成"待抵扣额",进下张应付结算单(§9.3-6)。
 *
 * supplier_id 必填防串户(P2-11,穿行场景13):结算单只允许带入**同供应商**待抵扣。
 * 状态:待抵扣 →(被结算单勾选带入)已抵扣(used_settle_bill_id 指向结算单;红冲释放回待抵扣)/ 作废。
 * 厂家结账凭证走通用凭证件(attachment refType=deduction)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_deduction")
public class Deduction extends BaseEntity {

    public static final String ST_PENDING = "待抵扣";
    public static final String ST_USED = "已抵扣";
    public static final String ST_VOID = "作废";

    private String dedNo;

    /** 供应商(NOT NULL,数据库层硬拦防串户) */
    private Long supplierId;

    /** 来源:兑换/厂家补贴 */
    private String dedSource;

    /** 抵扣金额(如厂家已结账 351.63) */
    private BigDecimal amount;

    private String dedStatus;

    /** 被使用的结算单 */
    private Long usedSettleBillId;

    /** 对应兑换活动区间说明 */
    private String periodDesc;
}
