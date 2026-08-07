package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 供应商档案(yc_vend_supplier)。
 * 口径:应付余额=期初+Σ采购-Σ退货-Σ抵扣-Σ付款,实时算不落表;停用保留历史往来。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_supplier")
public class Supplier extends BaseEntity {

    /** 供应商编码(租户内唯一) */
    private String supplierCode;

    /** 供应商名称 */
    private String supplierName;

    /** 联系方式(微信/电话) */
    private String contact;

    /** 结算方式:现结/月结/预付 */
    private String settleMethod;

    /** 账期天数(月结 N 天) */
    private Integer accountDays;

    /** 期初应付(上线向导录入,改动走调整留痕) */
    private BigDecimal openingPayable;

    /** 合作状态:合作中/停用 */
    private String coopStatus;

    private String remark;
}
