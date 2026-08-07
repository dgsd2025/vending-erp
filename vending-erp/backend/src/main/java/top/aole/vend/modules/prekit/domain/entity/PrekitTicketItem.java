package top.aole.vend.modules.prekit.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * 配货单明细(yc_vend_prekit_ticket_item):计划带出 / 实上架 / 带回 三列。
 * qty_loaded / qty_takeback 由核销(导入通道2)回填;plan_id 回链补货建议行做状态联动。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_prekit_ticket_item")
public class PrekitTicketItem extends BaseEntity {

    /** 配货单 ID */
    private Long ticketId;

    /** 商品 SKU */
    private Long productId;

    /** 来源补货建议行(yc_vend_replenish_plan;生成/执行时联动 plan_status) */
    private Long planId;

    /** 计划带出数量(生成时=建议量,装箱前可改) */
    private BigDecimal qtyPlanned;

    /** 实际上架数量(核销时从匹配转移单回填) */
    private BigDecimal qtyLoaded;

    /** 带回数量 = max(带出 − 上架, 0) */
    private BigDecimal qtyTakeback;
}
