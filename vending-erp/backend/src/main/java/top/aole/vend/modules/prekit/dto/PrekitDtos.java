package top.aole.vend.modules.prekit.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Pre-kit 配货单 / 出库上架页(p5)入参出参。
 */
public final class PrekitDtos {

    private PrekitDtos() {
    }

    /** 生成配货单:补货建议机器侧勾选行(qty 可改,空 = 用建议量) */
    @Data
    public static class GenerateReq {

        @Valid
        @NotEmpty(message = "至少勾选一行补货建议")
        private List<GenerateItem> items;
    }

    @Data
    public static class GenerateItem {

        @NotNull(message = "建议行 ID 不能为空")
        private Long planId;

        /** 带出量(可改;空 = 建议量) */
        @DecimalMin(value = "0", inclusive = false, message = "带出量必须大于 0")
        private BigDecimal qty;
    }

    /** 生成结果摘要 */
    @Data
    public static class GenerateResp {
        private int ticketCount;
        private int itemCount;
        private List<Long> ticketIds;
    }

    /** 改带出量(仅「已生成」可改) */
    @Data
    public static class ItemQtyReq {

        @NotNull(message = "带出量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "带出量必须大于 0")
        private BigDecimal qtyPlanned;
    }

    /** 手工转移单录入(兜底;确认后出库上架单进「预挂单」等导入冲抵) */
    @Data
    public static class ManualTransferReq {

        /** 出库上架 / 退库 */
        @NotNull(message = "单据类型不能为空(出库上架/退库)")
        private String docType;

        @NotNull(message = "机器不能为空")
        private Long machineId;

        @NotNull(message = "业务日期不能为空")
        private LocalDate bizDate;

        private String remark;

        @Valid
        @NotEmpty(message = "明细不能为空")
        private List<ManualTransferItem> items;
    }

    @Data
    public static class ManualTransferItem {

        @NotNull(message = "商品不能为空")
        private Long productId;

        @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "数量必须大于 0")
        private BigDecimal qty;

        private String slotNo;
    }
}
