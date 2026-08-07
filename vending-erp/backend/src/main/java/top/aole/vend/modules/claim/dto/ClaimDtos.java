package top.aole.vend.modules.claim.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** M3-4 索赔单 DTO 集 */
public final class ClaimDtos {

    private ClaimDtos() {
    }

    /** 发起索赔申请(盘亏归因=吞货/被盗行,或手工其他来源;金额默认=盘亏成本额,前端预填) */
    @Data
    public static class CreateReq {
        /** 索赔对象:厂家/平台 */
        @NotBlank(message = "索赔对象不能为空(厂家/平台)")
        private String claimTarget;
        @NotNull(message = "索赔金额不能为空")
        private BigDecimal amount;
        /** 来源盘点单 ID(盘亏发起时传;手工其他来源可空) */
        private Long sourceId;
        /** 来源盘点差异行 ID 列表:归因=吞货掉货/被盗 的盘亏行,发起后回填 stocktake_item.claim_id */
        private List<Long> stocktakeItemIds = new ArrayList<>();
        /** 申请说明(可选) */
        private String remark;
    }

    /** 到账登记(赔付凭证必传:先传 refType=claim 凭证再调本接口) */
    @Data
    public static class ReceiveReq {
        /** 入账账户(真实账户) */
        @NotNull(message = "入账账户不能为空")
        private Long accountId;
        /** 实际到账金额(缺省=索赔金额) */
        private BigDecimal receivedAmount;
        /** 到账时间(缺省=当前) */
        private LocalDateTime receivedTime;
    }

    /** 放弃(备注必填) */
    @Data
    public static class AbandonReq {
        @NotBlank(message = "放弃必须填写原因备注")
        private String remark;
    }

    /** 索赔单列表行 */
    @Data
    public static class ClaimRow {
        private Long id;
        private String claimNo;
        private String sourceType;
        private Long sourceId;
        private String claimTarget;
        private BigDecimal amount;
        private String claimStatus;
        private BigDecimal receivedAmount;
        private LocalDateTime receivedTime;
        private Long cashFlowId;
        private String remark;
        private LocalDateTime createTime;
        /** 已传凭证数(到账登记门禁提示用) */
        private Long attachmentCount;
    }

    /** 净损耗(§13.1:净损耗 = 损耗 − 已获赔) */
    @Data
    public static class NetShrinkageResp {
        /** 损耗侧:已确认 盘亏出库/报损 单成本额合计 */
        private BigDecimal lossAmount;
        /** 已获赔:索赔已到账金额合计 */
        private BigDecimal compensatedAmount;
        /** 净损耗 = 损耗 − 已获赔 */
        private BigDecimal netAmount;
        /** 口径区间(入账月,可空=全期) */
        private String fromPeriod;
        private String toPeriod;
    }
}
