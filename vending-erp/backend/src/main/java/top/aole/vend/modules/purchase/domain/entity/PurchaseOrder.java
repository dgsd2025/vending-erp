package top.aole.vend.modules.purchase.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 轻量订货单(yc_vend_purchase_order,P0-5):草稿级不进账,是"在途库存"的唯一数据生产者。
 * 状态:草稿/已下单/部分到货/已完成/已取消(状态流转集中在 PurchaseOrderService,禁散落 if-else)。
 */
@Data
@TableName("yc_vend_purchase_order")
public class PurchaseOrder {

    /** 状态常量(varchar 落库,中文值) */
    public static final String ST_DRAFT = "草稿";
    public static final String ST_PLACED = "已下单";
    public static final String ST_PARTIAL = "部分到货";
    public static final String ST_DONE = "已完成";
    public static final String ST_CANCELLED = "已取消";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    /** 订货单号,如 PO-20260806-001 */
    private String poNo;
    private Long supplierId;
    /** 预计到货日:超期未到 → 黄灯(接口返回 overdue 标记) */
    private LocalDate expectDate;
    private String poStatus;
    /** 预计总金额 */
    private BigDecimal totalAmount;
    private String remark;

    private Long createUser;
    private Long updateUser;
}
