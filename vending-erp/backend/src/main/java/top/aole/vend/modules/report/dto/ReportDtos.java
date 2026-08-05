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
}
