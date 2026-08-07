package top.aole.vend.modules.doc.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单据明细(yc_vend_doc_item):数量/单价/批次/金额;expect_qty 应收列支撑收货差异(P0-5)。
 */
@Data
@TableName("yc_vend_doc_item")
public class DocItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long docId;
    private Long productId;
    /** 货道号(出库上架/退库时精确到货道;来自后台补货记录) */
    private String slotNo;
    private BigDecimal boxQty;
    /** 数量(基本单位;恒为正数,方向由 doc_type 决定) */
    private BigDecimal qty;
    /** 应收数量(从 purchase_order_item 带入,收货差异=qty-expect_qty) */
    private BigDecimal expectQty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    /** 批次号(M2 FEFO 用) */
    private String batchNo;
    private LocalDate expireDate;
    /** 关联订货单明细(回写已收量,在途=Σ(订购-已收),M1-4 收货接上) */
    private Long poItemId;
    private String remark;

    private Long createUser;
    private Long updateUser;
}
