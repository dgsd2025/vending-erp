package top.aole.vend.modules.stocktake.money.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * M3-5 DTO 集:资金调整单 + 钱盘三核对。
 */
public final class CashMoneyDtos {

    private CashMoneyDtos() {
    }

    // ============================== 资金调整单 ==============================

    /** 建单入参:账户 + 带符号调整金额(+收/−支) + 原因枚举(其他必备注) */
    @Data
    public static class AdjustCreateReq {
        @NotNull(message = "账户必选")
        private Long accountId;
        /** 带符号:+ = 实际多于系统(收);− = 实际少于系统(支) */
        @NotNull(message = "调整金额必填(带符号:+收/−支)")
        private BigDecimal adjustAmount;
        @NotNull(message = "原因必选(盘盈/盘亏/手续费漏记/期初错/其他)")
        private String reason;
        private String remark;
        /** 来源钱盘核对记录(可空;核对页一键生成时回链) */
        private Long cashCheckId;
    }

    /** 资金调整单行(列表/详情) */
    @Data
    public static class AdjustRow {
        private Long docId;
        private String docNo;
        private String docStatus;
        private LocalDate bizDate;
        private LocalDateTime confirmAt;
        private String remark;
        private Long accountId;
        private String accountName;
        /** 收/支 */
        private String direction;
        private BigDecimal amount;
        private String reason;
        private Long cashCheckId;
    }

    // ============================== 钱盘三核对 ==============================

    /** 核对明细行(带出口状态) */
    @Data
    public static class CheckItemRow {
        private Long id;
        /** 账户/平台/应付 */
        private String itemType;
        private Long refId;
        private String refName;
        private BigDecimal systemAmount;
        private BigDecimal actualAmount;
        private BigDecimal diffAmount;
        private Long adjustDocId;
        private String exitAction;
        private Long sourceDocId;
        private String note;
    }

    /** 核对记录详情 */
    @Data
    public static class CheckDetailResp {
        private Long id;
        private String checkNo;
        private String checkPeriod;
        private String checkStatus;
        private String settleModeSnap;
        private Boolean platformSkipped;
        private String platformNote;
        private String remark;
        private LocalDateTime confirmAt;
        private LocalDateTime createTime;
        private List<CheckItemRow> accountItems = new ArrayList<>();
        private List<CheckItemRow> platformItems = new ArrayList<>();
        private List<CheckItemRow> payableItems = new ArrayList<>();
    }

    /** 核对记录列表行 */
    @Data
    public static class CheckListRow {
        private Long id;
        private String checkNo;
        private String checkPeriod;
        private String checkStatus;
        private Boolean platformSkipped;
        private LocalDateTime createTime;
        private LocalDateTime confirmAt;
        private Long diffCount;
    }

    /** 录实际数(整包:只传核对过的行) */
    @Data
    public static class SaveActualsReq {
        private List<ActualRow> rows = new ArrayList<>();
    }

    @Data
    public static class ActualRow {
        @NotNull
        private Long itemId;
        /** 实际数(手填);null = 清空回"未核对" */
        private BigDecimal actualAmount;
        private String note;
    }

    /** 应付不符出口入参:补录/红冲 */
    @Data
    public static class PayableExitReq {
        @NotNull(message = "出口必选:补录/红冲")
        private String action;
    }

    /** 应付不符出口响应(前端按此跳转,不重造录入/红冲页) */
    @Data
    public static class PayableExitResp {
        private String action;
        /** 补录 → 前端路由(采购/付款/抵扣录入所在页) */
        private String route;
        /** 红冲 → 对应单据 id(打开单据抽屉走既有红冲入口) */
        private Long sourceDocId;
        private String message;
    }

    /** 供应商应付余额快照行(查询映射) */
    @Data
    public static class SupplierPayableRow {
        private Long supplierId;
        private String supplierName;
        private BigDecimal payable;
        private Long lastSourceDocId;
    }
}
