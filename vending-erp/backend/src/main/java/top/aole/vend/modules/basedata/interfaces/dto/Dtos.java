package top.aole.vend.modules.basedata.interfaces.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * basedata 模块用到的小请求体集合(避免为每个两三字段的请求单开文件)。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 改状态通用请求(商品/供应商/机器/货道) */
    @Data
    public static class StatusReq {
        @NotBlank(message = "状态不能为空")
        private String targetStatus;
    }

    /** 别名绑定请求:后台商品编号+条码 → SKU */
    @Data
    public static class BindAliasReq {
        /** 后台商品编号(与条码至少填一个) */
        private String aliasCode;
        /** 后台条码 */
        private String aliasBarcode;
        @NotBlank(message = "后台商品名不能为空")
        private String aliasName;
        @NotNull(message = "必须选择绑定到哪个商品")
        private Long productId;
        /** 绑定来源,默认 人工 */
        private String bindSource;
    }

    /** 待绑定确认请求 */
    @Data
    public static class ConfirmPendingReq {
        @NotNull(message = "必须选择绑定到哪个商品")
        private Long productId;
    }

    /** 货道批量初始化请求:机器 × 货道号 */
    @Data
    public static class SlotInitReq {
        /** 方式一:直接给货道号列表(如 ["01","02","A3"]) */
        private List<String> slotNos;
        /** 方式二:给数量,自动生成 01..NN */
        private Integer slotCount;
        /** 初始化容量,默认 0 */
        private BigDecimal capacity;
    }

    /** 货道编辑请求:绑 SKU / 容量 / 状态 */
    @Data
    public static class SlotUpdateReq {
        /** 绑定 SKU;null 表示不改;0 表示解绑成空货道 */
        private Long productId;
        private BigDecimal capacity;
        /** 正常/停用/故障;null 不改 */
        private String slotStatus;
    }
}
