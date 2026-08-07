package top.aole.vend.modules.bi.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * BI 经营分析 DTO(M4-1,§10.1 维度×指标矩阵 + 单品四象限 + 连带/支付/缺货损失/调价对比)。
 *
 * 铁律:矩阵里取不到数的格子一律 null(前端显「—」),禁止造假数;
 * 毛利口径 = §13(实收 − 移动加权成本;兑换/线下补录收入 0;测试不计;无采购史成本 NULL)。
 */
public class BiDtos {

    // ============================== 维度×指标矩阵 ==============================

    /**
     * 六维通用矩阵行:各维度只填自己有的格子,其余 null。
     * 机器/品类/单品/货道/时段·星期/供应商 共用一个行结构,前端按 dim 挑列。
     */
    @Data
    public static class MatrixRow {
        /** 维度主键(机器id/单品id/供应商id;品类/时段用 null) */
        private Long key;
        /** 行名(机器名/品类名/商品名/机器名·货道号/时段标签/供应商名) */
        private String name;
        /** 附注(SKU 编码/品类/绑定商品等) */
        private String code;
        private String category;

        // ---- 销售额/销量 ----
        private BigDecimal salesQty;
        private BigDecimal salesAmt;
        /** 销售额占比 %(品类/时段/支付) */
        private BigDecimal sharePct;
        /** 日均销售额(机器维:销售额÷本月已过天数) */
        private BigDecimal dailyAvgAmt;

        // ---- 毛利额/毛利率(并排看,销售高≠赚钱) ----
        private BigDecimal grossProfit;
        private BigDecimal marginPct;
        /** 毛利贡献占比 %(品类维) */
        private BigDecimal grossSharePct;
        /** 该行含无成本 SKU 数(毛利只算已计部分) */
        private Integer noCostSkuCount;
        /** 单品维:是否有成本(false → 毛利显「—(成本待补)」) */
        private Boolean hasCost;

        // ---- 动销/周转 ----
        /** 动销率 %(品类维:有销量 SKU ÷ 在售 SKU) */
        private BigDecimal activeRate;
        private Integer soldSkuCount;
        private Integer onSaleSkuCount;
        /** 存销比(还能卖几天)=当前库存÷28天日均销量;日均 0 → null */
        private BigDecimal stockDays;
        /** 售罄率 %(月销量÷月上架量;无上架 → null) */
        private BigDecimal sellThroughPct;

        // ---- 损耗/缺货 ----
        private BigDecimal lossQty;
        private BigDecimal lossAmt;
        /** 货道维吞货率 %(吞货掉货件数÷出货量;需盘点记到货道级,记不到 → null) */
        private BigDecimal swallowRate;
        /** 缺货损失估算 ¥(锚点+增量能重建库存轨迹才有;否则 null) */
        private BigDecimal stockoutLossAmt;

        // ---- 供应商维专属 ----
        private BigDecimal purchaseAmt;
        private BigDecimal purchaseSharePct;
        /** 供货商品毛利贡献(按「该 SKU 累计采购额最大供应商」归属) */
        private BigDecimal marginContribution;
        private Integer attributedSkuCount;
        /** 实收差异率 %(已完成订货单:1−Σ实收/Σ订购;无完成单 → null) */
        private BigDecimal receiveDiffRate;

        // ---- 货道维专属 ----
        /** 货道当前推算库存(同 SKU 绑多货道拆不开 → null + shared) */
        private BigDecimal estQty;
        private Boolean estShared;
    }

    @Data
    public static class MatrixResp {
        private String month;
        private List<String> months = new ArrayList<>();
        private String dim;
        private LocalDateTime dataAsOf;
        /** 本月已过天数(日均分母;dataAsOf 在月内则截到 dataAsOf) */
        private Integer daysElapsed;
        private List<MatrixRow> rows = new ArrayList<>();
        /** 时段维专属:星期节律 7 行(其余维 null) */
        private List<MatrixRow> weekdayRows;
    }

    // ============================== 单品四象限 ==============================

    @Data
    public static class QuadrantPoint {
        private Long productId;
        private String name;
        private String skuCode;
        private String category;
        private BigDecimal salesQty;
        private BigDecimal salesAmt;
        private BigDecimal grossProfit;
        private BigDecimal marginPct;
        /** 明星/引流/利基/淘汰 */
        private String quadrant;
    }

    @Data
    public static class QuadrantResp {
        private String month;
        private List<String> months = new ArrayList<>();
        private LocalDateTime dataAsOf;
        /** 阈值 = 有销量且有成本单品的中位数 */
        private BigDecimal qtyMedian;
        private BigDecimal marginMedian;
        private List<QuadrantPoint> points = new ArrayList<>();
        /** 有销量但无成本(毛利率算不出)→ 不硬塞象限,单列「成本待补」 */
        private List<QuadrantPoint> noCostPoints = new ArrayList<>();
    }

    // ============================== 客单价/连带/支付方式 ==============================

    @Data
    public static class ComboRow {
        private String productA;
        private String productB;
        private Integer times;
    }

    @Data
    public static class PayMethodRow {
        private String payMethod;
        private Integer orderCount;
        private BigDecimal amt;
        private BigDecimal sharePct;
    }

    @Data
    public static class BasketResp {
        private String month;
        private List<String> months = new ArrayList<>();
        private LocalDateTime dataAsOf;
        /** 客单价 = 正常订单实收合计 ÷ 订单数(退款/兑换/测试/线下不入) */
        private BigDecimal avgOrderValue;
        private Integer orderCount;
        private Integer multiItemOrderCount;
        private BigDecimal multiItemSharePct;
        /** 同订单多件 TOP 组合(指导相邻货道摆放) */
        private List<ComboRow> combos = new ArrayList<>();
        private List<PayMethodRow> payMethods = new ArrayList<>();
    }

    // ============================== 缺货损失估算 ==============================

    @Data
    public static class StockoutLossRow {
        private Long machineId;
        private String machineName;
        private Long productId;
        private String productName;
        /** 月内可重建库存轨迹的天数(锚点覆盖不到的天不算) */
        private Integer coverageDays;
        /** 估算缺货天数(日末推算库存 ≤0) */
        private Integer stockoutDays;
        /** 有货日的日均毛利(¥/天) */
        private BigDecimal dailyGross;
        /** 估算损失 = 缺货天数 × 日均毛利 */
        private BigDecimal estLoss;
    }

    @Data
    public static class StockoutLossResp {
        private String month;
        private List<String> months = new ArrayList<>();
        private LocalDateTime dataAsOf;
        private List<StockoutLossRow> rows = new ArrayList<>();
        private BigDecimal totalEstLoss;
        /** 口径说明(估算方法透明) */
        private String note;
    }

    // ============================== 调价前后 14 天对比 ==============================

    @Data
    public static class PriceCompareRow {
        private Long productId;
        private String productName;
        private String skuCode;
        private BigDecimal oldPrice;
        private BigDecimal newPrice;
        private String effectDate;
        private String changeSource;
        /** 前/后窗口实际天数(后窗不足 14 天 → partial=true) */
        private Integer beforeDays;
        private Integer afterDays;
        private Boolean partial;
        private BigDecimal beforeAvgQty;
        private BigDecimal afterAvgQty;
        private BigDecimal beforeAvgAmt;
        private BigDecimal afterAvgAmt;
        private BigDecimal beforeGross;
        private BigDecimal afterGross;
        /** 日均销量变化 %(前窗日均 0 → null) */
        private BigDecimal qtyChangePct;
    }

    @Data
    public static class PriceCompareResp {
        private LocalDateTime dataAsOf;
        private List<PriceCompareRow> rows = new ArrayList<>();
    }
}
