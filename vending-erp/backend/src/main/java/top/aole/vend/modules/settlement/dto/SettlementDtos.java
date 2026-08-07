package top.aole.vend.modules.settlement.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** M3-3 平台结算双模式 DTO 集 */
public final class SettlementDtos {

    private SettlementDtos() {
    }

    // ============ 录入 ============

    /**
     * 录入(模式感知):PLATFORM 五要素全必填;DIRECT 只要 区间+账单额
     * (手续费/到账/账户是平台结算概念,直连核对单不适用,传了也置 0/空)。
     */
    @Data
    public static class BillCreateReq {
        @NotNull(message = "结算区间起不能为空")
        private LocalDate periodStart;
        @NotNull(message = "结算区间止不能为空")
        private LocalDate periodEnd;
        /** 平台账单销售额(两模式都必填) */
        @NotNull(message = "平台账单金额不能为空")
        private BigDecimal platformAmount;
        /** 平台手续费(PLATFORM 必填,可为 0) */
        private BigDecimal feeAmount;
        /** 实际到账(PLATFORM 必填) */
        private BigDecimal actualAmount;
        /** 入账账户(PLATFORM 必填真实账户) */
        private Long accountId;
        private String remark;
    }

    @Data
    public static class ResolveDiffReq {
        /** 差异复核结论(必填留痕) */
        private String note;
    }

    // ============ 列表/详情 ============

    @Data
    public static class BillRow {
        private Long id;
        private String stmtNo;
        private String modeSnap;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private BigDecimal platformAmount;
        private BigDecimal feeAmount;
        private BigDecimal actualAmount;
        private BigDecimal systemAmount;
        private BigDecimal diffSales;
        private BigDecimal diffArrival;
        /** 两差红绿灯:绿=|差|≤阈值(±1 元) */
        private Boolean salesDiffOk;
        private Boolean arrivalDiffOk;
        private Long accountId;
        private String accountName;
        private String stlStatus;
        private String confirmBy;
        private LocalDateTime confirmAt;
        private String diffNote;
        private String bookPeriod;
        /** 确认时回填了几笔销售(PLATFORM;op_log 同步留痕) */
        private Integer backfillCount;
        /** 已传凭证数(前端软提示;后端确认仍有硬门禁) */
        private Long attachmentCount;
    }

    /** 确认结果(PLATFORM 全链数字;DIRECT 只有系统额与漏单差) */
    @Data
    public static class ConfirmResult {
        private Long billId;
        private String mode;
        private BigDecimal systemAmount;
        private BigDecimal diffSales;
        private BigDecimal diffArrival;
        private Boolean salesDiffOk;
        private Boolean arrivalDiffOk;
        private String stlStatus;
        /** PLATFORM:本次回填 sale_record 笔数;DIRECT 恒 0 */
        private Integer backfillCount;
        /** PLATFORM:落流水条数(货款结算+手续费);DIRECT 恒 0(不落收入流水) */
        private Integer flowCount;
    }

    // ============ 总览(模式感知) ============

    /** 假设口径的一版参考数(UNSET 预览用,informal=true 标"非正式") */
    @Data
    public static class HypothesisView {
        private String mode;
        private String title;
        private String explain;
        /** 参考数:PLATFORM 假设=待结算余额;DIRECT 假设=同额但口径为"已入账销售" */
        private BigDecimal amount;
        private Boolean informal;
    }

    @Data
    public static class OverviewResp {
        private String mode;
        /** UNSET 时=待核实横幅原文;定型后 null */
        private String banner;
        /** 两差红灯阈值(±元) */
        private BigDecimal diffThreshold;

        // ---- PLATFORM ----
        /** 平台待结算余额(=Σ 未回填正常−退款;DIRECT 模式为 null——无虚账) */
        private BigDecimal pendingBalance;
        private Long pendingCount;
        private LocalDateTime pendingOldest;

        // ---- DIRECT ----
        /** 直连说明(无虚账,对账走核对单+钱盘) */
        private String directNote;

        // ---- UNSET:两模式各算一版参考数(非正式) ----
        private List<HypothesisView> hypothesis = new ArrayList<>();
    }

    // ============ 兑换活动 ROI(Scenario04 对冲闭环) ============

    @Data
    public static class ExchangeRoiResp {
        /** Σ兑换出货成本(移动加权快照,NULL 成本行不计) */
        private BigDecimal exchangeCost;
        /** 兑换出货件数 */
        private BigDecimal exchangeQty;
        /** 成本缺数行数(无采购史,禁按 0 硬算 §13.2-6) */
        private Long costMissingCount;
        /** Σ厂家补贴-已抵扣(已进应付结算单对冲) */
        private BigDecimal subsidyUsed;
        /** Σ厂家补贴-待抵扣(已确认未使用) */
        private BigDecimal subsidyPending;
        /** 补贴确认总额(待抵扣+已抵扣;作废不计) */
        private BigDecimal subsidyConfirmed;
        /** 净对冲结果 = 补贴确认总额 − 兑换成本(≥0=补贴够本,<0=兑换在亏) */
        private BigDecimal net;
    }

    // ============ M3-9 七律修复:红冲逆向 + 在途 aging ============

    /** 已核销单红冲逆向结果 */
    @Data
    public static class RedFlushResult {
        private Long billId;
        /** 退回填笔数(销售重新回待结算) */
        private int unbackfillCount;
        /** 反向流水条数(按账户×类别净额) */
        private int reverseFlowCount;
        /** 逆向后状态(待核对) */
        private String stlStatus;
    }

    /** 待结算最老账龄(驾驶舱「超期未结算」红灯只读数据口) */
    @Data
    public static class PendingAgingResp {
        private String mode;
        private BigDecimal pendingBalance;
        private Long pendingCount;
        /** 最早一笔待回填销售时间 */
        private java.time.LocalDateTime oldest;
        /** 挂账天数(无在途=null) */
        private Integer oldestDays;
        /** 超阈值红灯 */
        private boolean overdue;
        private int thresholdDays;
    }
}
