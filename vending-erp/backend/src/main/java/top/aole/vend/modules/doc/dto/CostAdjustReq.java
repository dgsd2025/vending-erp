package top.aole.vend.modules.doc.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 成本调整入参(P0-1 单价错专用):选原采购入库单的错误明细行 → 输入正确单价。
 */
@Data
public class CostAdjustReq {

    @Valid
    @NotEmpty(message = "至少选择一行要调整的明细")
    private List<Line> lines;

    private String remark;

    @Data
    public static class Line {
        @NotNull(message = "明细行 ID 不能为空")
        private Long docItemId;
        @NotNull(message = "正确单价不能为空")
        private BigDecimal correctPrice;
    }
}
