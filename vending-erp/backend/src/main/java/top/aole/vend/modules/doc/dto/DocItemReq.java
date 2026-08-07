package top.aole.vend.modules.doc.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单据明细入参。
 */
@Data
public class DocItemReq {

    @NotNull(message = "商品不能为空")
    private Long productId;
    private String slotNo;
    private BigDecimal boxQty;
    @NotNull(message = "数量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "数量必须大于0(方向由单据类型决定,不要传负数)")
    private BigDecimal qty;
    private BigDecimal expectQty;
    private BigDecimal unitPrice;
    private String batchNo;
    private LocalDate expireDate;
    private Long poItemId;
    private String remark;
}
