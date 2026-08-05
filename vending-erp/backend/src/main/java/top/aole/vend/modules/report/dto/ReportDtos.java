package top.aole.vend.modules.report.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表模块出入参(M1-6):毛利报表 / 进销存汇总 / 库存查询。
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    // ============================== 引擎输入事件(mybatis 直映) ==============================

    /** 仓库账流水事件(带单据类型,成本引擎按 biz_time 时序遍历) */
    @Data
    public static class LedgerEvent {
        private Long id;
        private Long productId;
        private String docType;
        private BigDecimal changeQty;
        private BigDecimal amount;
        private BigDecimal unitCost;
        private LocalDateTime bizTime;
    }

    /** 销售事件(毛利口径:正常计销售额,退款负,兑换收入0计成本,测试不计) */
    @Data
    public static class SaleEvent {
        private Long id;
        private Long productId;
        private Long machineId;
        private BigDecimal qty;
        private BigDecimal amountReceived;
        private String orderType;
        private LocalDateTime bizTime;
        private String bizPeriod;
    }

    // ============================== 毛利报表 ==============================

    @Data
    public static class GrossMarginRow {
        /** SKU 维=productId;机器维=machineId;未绑定行=null */
        private Long key;
        private String code;
        private String name;
        private BigDecimal salesQty;
        /** 销售额:正常 + 退款(负);兑换/线下补录收入 0 */
        private BigDecimal salesAmt;
        /** 加权成本(无采购史=null → 前端显「—(成本待补)」) */
        private BigDecimal costAmt;
        private BigDecimal grossProfit;
        /** 毛利率 %(salesAmt 为 0 或无成本时 null) */
        private BigDecimal marginPct;
        /** 是否有成本(false=无采购史,毛利显「—」) */
        private boolean hasCost = true;
        /** 机器维:该机器上无成本 SKU 个数(>0 表示成本仅部分) */
        private int noCostSkuCount;
    }

    @Data
    public static class GrossMarginResp {
        private String month;
        private String dim;
        private List<String> months = new ArrayList<>();
        private List<GrossMarginRow> rows = new ArrayList<>();
        /** 合计:销售额全口径;成本/毛利仅「有成本」行合计 */
        private BigDecimal totalSalesAmt;
        private BigDecimal totalCostAmt;
        private BigDecimal totalGrossProfit;
        private BigDecimal totalMarginPct;
        /** 无成本 SKU 数(毛利未计入合计) */
        private int noCostCount;
        /** 有成本行的销售额(毛利率分母口径说明用) */
        private BigDecimal costedSalesAmt;
        private LocalDateTime dataAsOf;
    }

    // ============================== 进销存汇总 ==============================

    /** 全局口径(仓库+机器合计,销售即出库),数量+金额(金额按移动加权) */
    @Data
    public static class InventorySummaryRow {
        private Long productId;
        private String code;
        private String name;
        private BigDecimal openingQty;
        private BigDecimal openingAmt;
        private BigDecimal inQty;
        private BigDecimal inAmt;
        private BigDecimal outQty;
        private BigDecimal outAmt;
        private BigDecimal closingQty;
        private BigDecimal closingAmt;
        private boolean hasCost = true;
    }

    @Data
    public static class InventorySummaryResp {
        private String month;
        private List<String> months = new ArrayList<>();
        private List<InventorySummaryRow> rows = new ArrayList<>();
        private InventorySummaryRow total;
        private LocalDateTime dataAsOf;
    }

    // ============================== 库存查询 ==============================

    @Data
    public static class StockMachineCol {
        private Long machineId;
        private String machineName;
    }

    @Data
    public static class StockRow {
        private Long productId;
        private String code;
        private String name;
        private String category;
        private String productStatus;
        private BigDecimal warehouseQty;
        /** machineId → 推算库存 */
        private Map<Long, BigDecimal> machineQty = new LinkedHashMap<>();
        private BigDecimal totalQty;
        /** 当前移动加权单位成本(无采购史=null) */
        private BigDecimal unitCost;
        /** 成本金额 = 合计 × 单位成本 */
        private BigDecimal amount;
        /** 负库存红灯(仓库或任一机器为负) */
        private boolean negative;
    }

    @Data
    public static class StockResp {
        private List<StockMachineCol> machines = new ArrayList<>();
        private List<StockRow> rows = new ArrayList<>();
        private BigDecimal totalAmount;
        private BigDecimal warehouseAmount;
        private BigDecimal machineAmount;
        private int negativeCount;
        /** 数据截至水印:流水/销售/快照三处最大业务时间 */
        private LocalDateTime dataAsOf;
    }

    /** 单品流水(点开看流水,p13) */
    @Data
    public static class StockLedgerRow {
        private Long id;
        private LocalDateTime bizTime;
        private String docNo;
        private String docType;
        private String locationType;
        private String machineName;
        private BigDecimal changeQty;
        private BigDecimal balanceQty;
        private BigDecimal unitCost;
        private BigDecimal amount;
    }

    // ============================== 成本重算 ==============================

    @Data
    public static class RecalcResp {
        /** 回写 sale_record.cost_amount 条数 */
        private int saleUpdated;
        /** 回写 stock_ledger 出库/转移行成本快照条数 */
        private int ledgerUpdated;
        private int products;
    }

    // ============================== 单品详情(M1-9,p14 体检报告) ==============================

    /** 趋势点(周/日通用):label=周一日期或天日期 */
    @Data
    public static class TrendPoint {
        private String label;
        private BigDecimal salesQty = BigDecimal.ZERO;
        private BigDecimal salesAmt = BigDecimal.ZERO;
        /** 毛利(无成本销售不计,null=该期全无成本) */
        private BigDecimal grossProfit = BigDecimal.ZERO;
    }

    /** 机器分布行(30 天销量 / 现存) */
    @Data
    public static class MachineDistRow {
        private Long machineId;
        private String machineName;
        private BigDecimal salesQty30 = BigDecimal.ZERO;
        /** 该机现存(锚点+增量推算) */
        private BigDecimal stockQty;
    }

    /** 采购史行(来自 采购入库/期初 已过账流水) */
    @Data
    public static class PurchaseHistRow {
        private LocalDateTime bizTime;
        private String docNo;
        private String docType;
        private String supplierName;
        private BigDecimal qty;
        private BigDecimal unitCost;
        private BigDecimal amount;
    }

    @Data
    public static class ProductOverviewResp {
        // 档案
        private Long productId;
        private String skuCode;
        private String productName;
        private String category;
        private String productStatus;
        private String unit;
        private BigDecimal boxSpec;
        private Integer shelfLifeDays;
        private BigDecimal refPrice;
        // 库存两级
        private BigDecimal warehouseQty;
        private BigDecimal machineQtyTotal;
        private BigDecimal totalQty;
        private BigDecimal unitCost;
        private BigDecimal stockAmount;
        private boolean hasCost = true;
        // 30 天口径(以 dataAsOf 为"今天")
        private BigDecimal salesQty30 = BigDecimal.ZERO;
        private BigDecimal salesAmt30 = BigDecimal.ZERO;
        private BigDecimal grossProfit30;
        private BigDecimal marginPct30;
        private BigDecimal dailyAvg30 = BigDecimal.ZERO;
        /** 够卖天数 = 合计现存 ÷ 日均(日均 0 → null 显「—」) */
        private BigDecimal daysOfStock;
        // 走势 + 分布 + 采购史
        private List<TrendPoint> weeklyTrend = new ArrayList<>();
        private List<MachineDistRow> machineDist = new ArrayList<>();
        private List<PurchaseHistRow> purchaseHist = new ArrayList<>();
        private BigDecimal purchaseTotalQty = BigDecimal.ZERO;
        private BigDecimal purchaseTotalAmt = BigDecimal.ZERO;
        private LocalDateTime dataAsOf;
    }

    // ============================== 机器详情(M1-9,p15) ==============================

    /** 货道行(planogram 格子) */
    @Data
    public static class SlotRow {
        private Long slotId;
        private String slotNo;
        private Long productId;
        private String productName;
        private String skuCode;
        private BigDecimal capacity;
        private BigDecimal currentQty;
        private String slotStatus;
    }

    /** 本机 SKU 销量行(30 天 TOP) */
    @Data
    public static class SkuSalesRow {
        private Long productId;
        private String skuCode;
        private String productName;
        private BigDecimal salesQty30 = BigDecimal.ZERO;
        private BigDecimal salesAmt30 = BigDecimal.ZERO;
    }

    /** 补货史行(该机转移单:出库上架/退库) */
    @Data
    public static class TransferHistRow {
        private Long docId;
        private String docNo;
        private String docType;
        private String docStatus;
        private java.time.LocalDate bizDate;
        private String sourceType;
        private int itemCount;
        private BigDecimal totalQty;
    }

    @Data
    public static class MachineOverviewResp {
        // 档案
        private Long machineId;
        private String machineCode;
        private String machineName;
        private String deviceId;
        private String location;
        private String model;
        private Integer slotCount;
        private String machineStatus;
        private String onlineDate;
        // 当月(dataAsOf 所在月)经营
        private String month;
        private BigDecimal monthSalesAmt = BigDecimal.ZERO;
        private BigDecimal monthSalesQty = BigDecimal.ZERO;
        private BigDecimal monthGrossProfit;
        private BigDecimal monthMarginPct;
        /** 全场占比 %(本机当月销售额 ÷ 全场当月销售额) */
        private BigDecimal salesSharePct;
        private BigDecimal dailyAvgAmt = BigDecimal.ZERO;
        // 机内库存(推算合计 / 货道容量合计)
        private BigDecimal machineStockQty = BigDecimal.ZERO;
        private BigDecimal capacityTotal = BigDecimal.ZERO;
        // 近 14 天走势(以 dataAsOf 为终点)
        private List<TrendPoint> dailyTrend = new ArrayList<>();
        // 本机 SKU 销量 TOP(30 天)
        private List<SkuSalesRow> topSkus = new ArrayList<>();
        // 货道 planogram + 补货史
        private List<SlotRow> slots = new ArrayList<>();
        private List<TransferHistRow> transferHist = new ArrayList<>();
        private LocalDateTime dataAsOf;
    }
}
