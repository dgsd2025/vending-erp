package top.aole.vend.modules.purchase.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订货单明细(yc_vend_purchase_order_item):订购/已收两列。
 * 在途 = Σ(qty_ordered − qty_received),补货公式(M2)"当前库存(机内+在途)"从这里取数。
 * qty_received 唯一写手 = 采购入库单确认回写(PurchaseReceiptListener)。
 */
@Data
@TableName("yc_vend_purchase_order_item")
public class PurchaseOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long poId;
    private Long productId;
    /** 订购数量 */
    private BigDecimal qtyOrdered;
    /** 已收数量(收货入库单确认时回写) */
    private BigDecimal qtyReceived;
    /** 预计单价 */
    private BigDecimal unitPrice;
    /** 预计金额 */
    private BigDecimal amount;

    private Long createUser;
    private Long updateUser;
}
