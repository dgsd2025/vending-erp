package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 补货参数(yc_vend_replenish_config)。
 * (R,S) 模型输入,全局+按 SKU/机器覆盖(细覆盖粗);每次改动必须写 op_log(旧值→新值→改动人)。
 * uk=(tenant_id, machine_id, product_id),0=不限。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_replenish_config")
public class ReplenishConfig extends BaseEntity {

    /** 作用域:全局/SKU/机器/机器×SKU */
    private String scopeType;

    /** SKU(0=不限) */
    private Long productId;

    /** 机器(0=不限) */
    private Long machineId;

    /** 补货周期 R(天) */
    private Integer cycleDays;

    /** 服务水平(Z 系数查表用) */
    private BigDecimal serviceLevel;

    /** 提前期 L(天) */
    private BigDecimal leadTimeDays;

    /** 临期预警天数 */
    private Integer expireWarnDays;
}
