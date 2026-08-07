package top.aole.vend.modules.monthly.dto;

import lombok.Data;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settlement.dto.SettlementDtos;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 月度报表包 DTO(M4-4,调研 §10.3 七件套 + §10.2 财务月度工作日历)。
 *
 * 本模块只读:所有数字来自已有服务(report/finreport/money/settle/settlement/stocktake/bi),
 * 一律聚合不重算(开发铁律#7 库存只能被单据改、单一真相源)。
 */
public class MonthlyDtos {

    // ==================== 报表包总览(前六件套,供预览 Tab)====================

    @Data
    public static class PackageResp {
        /** 当前口径月 */
        private String month;
        /** 可选月份列表(有数据的 book_period) */
        private List<String> months = new ArrayList<>();
        /** 数据截至水印(与报表页同源) */
        private LocalDateTime dataAsOf;
        /** 该月是否已锁账(利润表传导) */
        private boolean locked;

        /** ① 进销存汇总表(复用 ReportService.inventorySummary) */
        private ReportDtos.InventorySummaryResp inventory;
        /** ② 简版利润表(复用 ProfitReportService.monthly) */
        private FinReportDtos.ProfitResp profit;
        /** ③ 现金流水汇总(按账户/类别 + 月末余额,聚合 CashFlowQueryService) */
        private CashflowSummary cashflow;
        /** ④ 往来表(应付明细 + 平台待结算) */
        private PayableSummary payable;
        /** ⑤ 损耗报表(按原因,复用 StocktakeService.lossStats 过滤本月) */
        private LossSummary loss;
        /** ⑥ 资产快照 + 净家底趋势(复用 AssetSnapshotService) */
        private AssetSummary asset;
    }

    // ==================== ③ 现金流水汇总 ====================

    @Data
    public static class CashflowSummary {
        private String month;
        /** 按账户聚合(本月收/支/净额) */
        private List<CashflowRow> byAccount = new ArrayList<>();
        /** 按类别(pl_line)聚合 */
        private List<CashflowRow> byCategory = new ArrayList<>();
        /** 各账户月末余额 = 期初 + Σ(book_period ≤ 本月)净额 */
        private List<AccountBalanceRow> monthEndBalances = new ArrayList<>();
        private BigDecimal totalInflow = BigDecimal.ZERO;
        private BigDecimal totalOutflow = BigDecimal.ZERO;
        private BigDecimal totalNet = BigDecimal.ZERO;
        /** 月末现金合计(所有真实账户月末余额之和) */
        private BigDecimal monthEndCashTotal = BigDecimal.ZERO;
    }

    @Data
    public static class CashflowRow {
        /** 账户名 或 类别(pl_line)名 */
        private String name;
        private BigDecimal inflow = BigDecimal.ZERO;
        private BigDecimal outflow = BigDecimal.ZERO;
        private BigDecimal net = BigDecimal.ZERO;
        private long count;
    }

    @Data
    public static class AccountBalanceRow {
        private Long accountId;
        private String accountName;
        private String accountType;
        private BigDecimal openingBalance;
        private BigDecimal monthEndBalance;
        private boolean virtual;
    }

    // ==================== ④ 往来表 ====================

    @Data
    public static class PayableSummary {
        /** 应付余额非零的供应商明细 */
        private List<SettleDtos.SupplierOverviewRow> suppliers = new ArrayList<>();
        /** 应付合计(余额为正的供应商求和) */
        private BigDecimal payableTotal = BigDecimal.ZERO;
        /** 预付款合计(余额为负) */
        private BigDecimal prepayTotal = BigDecimal.ZERO;
        /** 逾期账龄(驾驶舱红灯口径) */
        private SettleDtos.PayableAgingResp aging;
        /** 平台待结算(结算模式传导) */
        private SettlementDtos.PendingAgingResp platformPending;
    }

    // ==================== ⑤ 损耗报表 ====================

    @Data
    public static class LossSummary {
        private String month;
        /** 本月损耗按原因(itemCount/qty/amount) */
        private List<StocktakeDtos.LossStatRow> rows = new ArrayList<>();
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private BigDecimal totalQty = BigDecimal.ZERO;
        /** 金额最大的原因(治理优先级) */
        private String topReason;
    }

    // ==================== ⑥ 资产快照 + 趋势 ====================

    @Data
    public static class AssetSummary {
        /** 当前净家底快照(复用 AssetSnapshotService.current) */
        private FinReportDtos.AssetSnapshotResp snapshot;
        /** 净家底趋势(近 12 个月归档快照,复用 trend) */
        private List<FinReportDtos.SnapshotRow> trend = new ArrayList<>();
    }

    // ==================== ⑦ 月度经营分析报告(固定七节)====================

    @Data
    public static class AnalysisReport {
        private String period;
        private List<String> months = new ArrayList<>();
        private boolean locked;
        /** AI 起草的全文综述(mock 时带 [MOCK];顶部 🔬 徽标看四件套) */
        private String aiText;
        /** 透明四件套锚点(前端 <LlmTransparencyBadge :call-id>) */
        private Long llmCallId;
        private boolean cacheHit;
        /** 生效模型(mock(kimi-k2) / 真实模型名) */
        private String model;
        /** 是否 mock 档(前端显 [MOCK] 徽标) */
        private boolean mock;
        /** 固定七节:经营概况/销售分析/利润分析/库存与损耗/钱账健康/异常与风险/下月改进建议 */
        private List<AnalysisSection> sections = new ArrayList<>();
    }

    @Data
    public static class AnalysisSection {
        /** 行键(overview/sales/profit/inventory/money/risk/improvement) */
        private String key;
        private String title;
        /** 规则引擎起草的本节叙述(数字全部来自规则,LLM 不算数) */
        private String narrative;
        /** 本节关键数据点,每点带数据溯源 */
        private List<DataPoint> dataPoints = new ArrayList<>();
    }

    @Data
    public static class DataPoint {
        private String label;
        private String value;
        /** 数据溯源:该数字来自哪张报表/服务(老板可回溯核对) */
        private String source;

        public DataPoint() {
        }

        public DataPoint(String label, String value, String source) {
            this.label = label;
            this.value = value;
            this.source = source;
        }
    }

    // ==================== 财务月度工作日历(§10.2)====================

    @Data
    public static class CalendarBoard {
        private String month;
        private List<CalendarDay> days = new ArrayList<>();
        /** 待人工项计数(看板红点) */
        private int pendingCount;
    }

    @Data
    public static class CalendarDay {
        /** 日(1/2/3/5) */
        private int day;
        /** 财务做什么 */
        private String financeAction;
        /** 系统自动做什么 */
        private String systemAuto;
        /** 状态:AUTO_DONE(自动完成) / PENDING_MANUAL(待人工) / IN_PROGRESS(进行中) */
        private String status;
        /** 状态一句话依据(规则判定,可溯源) */
        private String note;
    }
}
