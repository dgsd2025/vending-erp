package top.aole.vend.modules.finreport.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * M3-6 DTO:资产快照(即时/归档/趋势)+ 简版利润表。
 */
public class FinReportDtos {

    private FinReportDtos() {
    }

    // ============================== 资产快照 ==============================

    /** 即时资产快照(p10 首屏):五分项 + 净资产 + 各项下钻来源列表 */
    @Data
    public static class AssetSnapshotResp {
        /** 快照计算时刻 */
        private LocalDateTime asOf;
        /** 数据截至水印(导入侧最大业务时间,与报表页同源) */
        private LocalDateTime dataAsOf;
        /** 结算模式(UNSET 时待结算恒 0 + 横幅传导) */
        private String settleMode;
        private String settleBanner;

        /** ① 库存资产(成本):仓库+机器 */
        private BigDecimal inventoryAmount;
        private BigDecimal warehouseAmount;
        private BigDecimal machineAmount;
        /** ② 平台待结算 */
        private BigDecimal platformPending;
        /** ③ 账户现金合计(真实账户) */
        private BigDecimal cashTotal;
        /** ④ 索赔应收(申请中) */
        private BigDecimal claimReceivable;
        /** ⑤ 应付供应商合计 */
        private BigDecimal payableTotal;
        /** 净流动资产 = ①+②+③+④−⑤ */
        private BigDecimal netAsset;

        /** 下钻:库存来源(有成本且有量的 SKU,按金额降序) */
        private List<InventoryRow> inventoryRows = new ArrayList<>();
        /** 下钻:待结算按业务月 */
        private List<PendingRow> pendingRows = new ArrayList<>();
        /** 下钻:真实账户余额 */
        private List<CashRow> cashRows = new ArrayList<>();
        /** 下钻:申请中的索赔 */
        private List<ClaimItemRow> claimRows = new ArrayList<>();
        /** 下钻:应付余额非零的供应商 */
        private List<PayableRow> payableRows = new ArrayList<>();

        /** 最近一次归档快照(算"比上月 ±"用;没有=null) */
        private String prevPeriod;
        private BigDecimal prevNetAsset;
    }

    @Data
    public static class InventoryRow {
        private Long productId;
        private String code;
        private String name;
        private BigDecimal qty;
        private BigDecimal amount;
    }

    @Data
    public static class PendingRow {
        private String period;
        private Long cnt;
        private BigDecimal amount;
    }

    @Data
    public static class CashRow {
        private Long accountId;
        private String accountName;
        private String accountType;
        private BigDecimal balance;
        /** 已停用账户(历史余额仍计入) */
        private boolean disabled;
    }

    @Data
    public static class ClaimItemRow {
        private Long claimId;
        private String claimNo;
        private String claimTarget;
        private BigDecimal amount;
        private LocalDateTime createTime;
    }

    @Data
    public static class PayableRow {
        private Long supplierId;
        private String supplierName;
        private BigDecimal balance;
        /** 余额为负 = 预付款 */
        private boolean prepay;
    }

    /** 归档行(月度归档列表/趋势折线共用) */
    @Data
    public static class SnapshotRow {
        private Long id;
        private String period;
        private BigDecimal inventoryAmount;
        private BigDecimal platformPending;
        private BigDecimal cashTotal;
        private BigDecimal claimReceivable;
        private BigDecimal payableTotal;
        private BigDecimal netAsset;
        private LocalDateTime updateTime;
        /** 该月已锁账 → 归档快照永不重算 */
        private boolean locked;
    }

    // ============================== 简版利润表 ==============================

    /** 简版利润表(按入账月;§13.1 行结构定死) */
    @Data
    public static class ProfitResp {
        private String period;
        private List<String> months = new ArrayList<>();
        /** 该月已锁账 → 已归档不重算 */
        private boolean locked;
        private String lockedNote;
        /** 结算模式横幅传导(UNSET 时资金侧只出假设口径) */
        private String settleMode;
        private String settleBanner;
        private List<PlRow> rows = new ArrayList<>();
        /** 经营利润 = 毛利 − 手续费 − 杂费 − 损耗 ± 成本调整 + 其他收入 ± 上期调整(§13.1) */
        private BigDecimal operatingProfit;
        /** 本金往来(不进利润表)当月净额,信息展示防"钱对不上"疑问 */
        private BigDecimal nonPlNet;
        /** 已核销结算单的锁后补导提示(只提示不改状态,P0-2) */
        private List<LockDiffRow> lockDiffNotes = new ArrayList<>();
    }

    /** 利润表一行:amount 为对经营利润的带符号贡献(收入正/费用负) */
    @Data
    public static class PlRow {
        /** 行键(测试/前端定位用,如 salesIncome/grossProfit/shrinkage…) */
        private String key;
        /** 行名(= pl_line 中文或小计名) */
        private String label;
        private BigDecimal amount;
        /** 小计/合计行(毛利、经营利润) */
        private boolean subtotal;
        /** 行注释人话(取数口径/一句话解释) */
        private String note;
    }

    @Data
    public static class LockDiffRow {
        private Long settlementId;
        private String stmtNo;
        private String periodStart;
        private String periodEnd;
        private String stlStatus;
        private String note;
    }
}
