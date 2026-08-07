package top.aole.vend.modules.purchase.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购入库单(收货单)建单/改草稿入参。
 * 两种来源共用:①从订货单一键生成后修改实收 ②无订单直接录入(兜底,现状小生意常态)。
 */
@Data
public class ReceiptCreateReq {

    @NotNull(message = "供应商不能为空")
    private Long supplierId;

    @NotNull(message = "入库日期不能为空")
    private LocalDate bizDate;

    /** 关联订货单(无订单直录时为空) */
    private Long purchaseOrderId;

    private String remark;

    @Valid
    @NotEmpty(message = "入库明细不能为空")
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "商品不能为空")
        private Long productId;

        /** 实收数量 */
        @NotNull(message = "实收数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "实收数量必须大于0")
        private BigDecimal qty;

        /** 应收数量(从订货单带入;直录单为空) */
        private BigDecimal expectQty;

        @NotNull(message = "进货单价不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "进货单价必须大于0")
        private BigDecimal unitPrice;

        /** 关联订货单明细(确认时回写 qty_received) */
        private Long poItemId;

        private String remark;
    }
}
