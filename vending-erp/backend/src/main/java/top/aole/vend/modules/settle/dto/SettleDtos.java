package top.aole.vend.modules.settle.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 应付全链 DTO(M3-2)。查询行沿用 Map 聚合口(与 purchase.receiptList 一致),这里只放请求体与结构化响应。
 */
public final class SettleDtos {

    private SettleDtos() {
    }

    /** 结算单老板复核确认:勾选带入的同供应商待抵扣(可空=不带) */
    @Data
    public static class BillConfirmReq {
        private List<Long> deductionIds;
    }

    /** 付款单录入:三要素 付给谁/从哪个账户/多少钱 + 可选核销结算单 */
    @Data
    public static class PaymentCreateReq {
        @NotNull(message = "付给谁(supplierId)必填")
        private Long supplierId;
        @NotNull(message = "从哪个账户(accountId)必填")
        private Long accountId;
        @NotNull(message = "付款金额必填")
        @DecimalMin(value = "0.01", message = "付款金额必须大于 0")
        private BigDecimal amount;
        /** 可选:核销哪张应付结算单(不强制逐单核销;不填=预付/冲余额) */
        private Long settleBillId;
        private String remark;
    }

    /** 抵扣确认单录入(厂家结账凭证走通用凭证件 refType=deduction) */
    @Data
    public static class DeductionCreateReq {
        @NotNull(message = "供应商必填(防串户,P2-11)")
        private Long supplierId;
        /** 兑换/厂家补贴 */
        @NotBlank(message = "来源必填:兑换/厂家补贴")
        private String dedSource;
        @NotNull(message = "抵扣金额必填")
        @DecimalMin(value = "0.01", message = "抵扣金额必须大于 0")
        private BigDecimal amount;
        /** 对应兑换活动区间说明(如"7月兑换活动 厂家已结账") */
        private String periodDesc;
    }

    /** 差异挂起处理:补说明闭环 */
    @Data
    public static class DiffResolveReq {
        @NotBlank(message = "差异说明必填(补说明或改单,§9.2)")
        private String note;
    }

    /** 供应商往来卡(p8):余额各分项实时算 */
    @Data
    public static class SupplierOverviewRow {
        private Long supplierId;
        private String supplierCode;
        private String supplierName;
        private String contact;
        private String settleMethod;
        private Integer accountDays;
        private String coopStatus;
        private BigDecimal openingPayable;
        private BigDecimal purchaseTotal;
        private BigDecimal returnTotal;
        private BigDecimal deductionTotal;
        private BigDecimal paymentTotal;
        /** 应付余额=期初+Σ采购−Σ退货−Σ抵扣−Σ付款;负数=预付款 */
        private BigDecimal balance;
        /** 余额为负 → 显示"预付款" */
        private boolean prepay;
        /** 逾期黄灯:有未付清结算单已过到期日 */
        private boolean overdue;
        private Integer overdueDays;
        /** 待抵扣张数(提示下张结算单可带) */
        private long pendingDeductions;
        /** 待冲抵红字张数 */
        private long pendingRedBills;
    }

    /** 对账单:期初 + 明细 + 期末(一键导出) */
    @Data
    public static class StatementResp {
        private Long supplierId;
        private String supplierName;
        private BigDecimal opening;
        private BigDecimal closing;
        /** 行:bizDate/refNo/lineType(拿货/拿货红冲/退货/抵扣/付款)/amount/balance/remark */
        private List<Map<String, Object>> lines;
    }

    /** 应付逾期 aging(M3-9 七律修复 P1-5:驾驶舱红灯②的只读数据口) */
    @Data
    public static class PayableAgingResp {
        /** 逾期供应商数(=驾驶舱红灯计数) */
        private int overdueCount;
        /** 最长逾期天数 */
        private int maxOverdueDays;
        /** 逾期明细行(只含逾期供应商) */
        private List<PayableAgingRow> rows = new java.util.ArrayList<>();
    }

    @Data
    public static class PayableAgingRow {
        private Long supplierId;
        private String supplierName;
        private BigDecimal balance;
        private Integer overdueDays;
    }
}
