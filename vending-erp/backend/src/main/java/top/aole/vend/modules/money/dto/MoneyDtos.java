package top.aole.vend.modules.money.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** M3-1 钱账基座 DTO 集(账户/流水/凭证/结算模式) */
public final class MoneyDtos {

    private MoneyDtos() {
    }

    // ============ 账户 ============

    @Data
    public static class AccountCreateReq {
        @NotBlank(message = "账户名不能为空")
        private String accountName;
        /** 中文类型:微信/支付宝/银行卡/现金/平台待结算/老板垫付 */
        @NotBlank(message = "账户类型不能为空")
        private String accountType;
        /** 可选:建户即设期初(等价于建户后立刻调一次期初设置,同样只此一次) */
        private BigDecimal openingBalance;
        private String remark;
    }

    @Data
    public static class AccountUpdateReq {
        @NotBlank(message = "账户名不能为空")
        private String accountName;
    }

    @Data
    public static class OpeningReq {
        @NotNull(message = "期初余额不能为空")
        private BigDecimal openingBalance;
    }

    @Data
    public static class StatusReq {
        /** 1=启用 0=停用 */
        @NotNull(message = "目标状态不能为空")
        private Integer targetStatus;
    }

    /** 账户行(带实时余额=期初+Σ流水) */
    @Data
    public static class AccountRow {
        private Long id;
        private String accountName;
        private String accountType;
        private Boolean isVirtual;
        private BigDecimal openingBalance;
        private LocalDateTime openingSetAt;
        /** 期初是否已锁定(设过一次) */
        private Boolean openingLocked;
        private BigDecimal balance;
        private Integer status;
    }

    // ============ 流水 ============

    /** 流水行(只读;带账户名) */
    @Data
    public static class FlowRow {
        private Long id;
        private String flowNo;
        private Long accountId;
        private String accountName;
        private String direction;
        private BigDecimal amount;
        private String category;
        private String plLine;
        private String refDocType;
        private Long refDocId;
        private LocalDateTime bizTime;
        private String bookPeriod;
        private String remark;
    }

    /** pl_line × 入账月 聚合行(利润表取数口) */
    @Data
    public static class PlSummaryRow {
        private String plLine;
        private String period;
        private BigDecimal inflow;
        private BigDecimal outflow;
        /** 净额 = 收 − 支 */
        private BigDecimal net;
    }

    // ============ 凭证 ============

    @Data
    public static class AttachmentRow {
        private Long id;
        private String refType;
        private Long refId;
        private String attType;
        private String fileName;
        private LocalDateTime createTime;
        private Long uploadBy;
    }

    // ============ 结算模式 ============

    @Data
    public static class SettleModeResp {
        /** UNSET / PLATFORM / DIRECT */
        private String mode;
        /** UNSET 时的前端横幅文案;已定型为 null */
        private String banner;
        /** 各模式一句话说明(前端选择器用) */
        private List<ModeOption> options = new ArrayList<>();
    }

    @Data
    public static class ModeOption {
        private String mode;
        private String label;
        private String description;

        public ModeOption() {
        }

        public ModeOption(String mode, String label, String description) {
            this.mode = mode;
            this.label = label;
            this.description = description;
        }
    }

    @Data
    public static class SettleModeReq {
        /** PLATFORM / DIRECT / UNSET */
        @NotBlank(message = "结算模式不能为空")
        private String mode;
    }
}
