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
 * 订货单建单/改草稿入参(P0-5 轻量订货单:草稿级不进账)。
 */
@Data
public class PoCreateReq {

    @NotNull(message = "供应商不能为空")
    private Long supplierId;

    /** 预计到货日(超期未到 → 黄灯) */
    private LocalDate expectDate;

    private String remark;

    @Valid
    @NotEmpty(message = "订货明细不能为空")
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "商品不能为空")
        private Long productId;

        @NotNull(message = "订购数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "订购数量必须大于0")
        private BigDecimal qtyOrdered;

        /** 预计单价(可空,收货时可改) */
        private BigDecimal unitPrice;
    }
}
