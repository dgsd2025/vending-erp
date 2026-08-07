package top.aole.vend.modules.monthly.service;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.service.LlmGateway;
import top.aole.vend.modules.bi.dto.BiDtos;
import top.aole.vend.modules.bi.service.BiService;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.monthly.dto.MonthlyDtos;
import top.aole.vend.modules.monthly.dto.MonthlyDtos.AnalysisReport;
import top.aole.vend.modules.monthly.dto.MonthlyDtos.AnalysisSection;
import top.aole.vend.modules.monthly.dto.MonthlyDtos.DataPoint;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.service.ReportService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 《月度经营分析报告》起草服务(M4-4 第七件套,§10.3 + §12.1 #5)。
 *
 * 固定七节:经营概况 / 销售分析 / 利润分析 / 库存与损耗 / 钱账健康 / 异常与风险 / 下月改进建议。
 * 铁律#7:规则引擎出数字(每节 dataPoints 带数据溯源),LLM 只把综述"说顺"(scene=MONTHLY,mock 带 [MOCK])。
 * 幂等:bizKey = month:period,当日复用不重烧钱(§4.19);force=true 重新起草。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyAnalysisService {

    private final MonthlyReportService monthlyReportService;
    private final ReportService reportService;
    private final BiService biService;
    private final LlmGateway llmGateway;

    // 数据溯源标签
    private static final String SRC_PROFIT = "简版利润表";
    private static final String SRC_ASSET = "资产快照";
    private static final String SRC_INV = "进销存汇总表";
    private static final String SRC_LOSS = "损耗报表";
    private static final String SRC_CASH = "现金流水汇总";
    private static final String SRC_PAYABLE = "往来表";
    private static final String SRC_BI_MACHINE = "BI·六维矩阵(机器)";
    private static final String SRC_BI_QUADRANT = "BI·单品四象限";
    private static final String SRC_BI_STOCKOUT = "BI·缺货损失";
    private static final String SRC_STOCK = "库存查询";

    public AnalysisReport analyze(String month, boolean force) {
        // 全包数字先由聚合服务算好(口径月在这里统一解析)
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(month);
        String period = pkg.getMonth();

        AnalysisReport report = new AnalysisReport();
        report.setPeriod(period);
        report.setMonths(pkg.getMonths());
        report.setLocked(pkg.isLocked());

        // BI 侧取数(销售/缺货)
        BiDtos.MatrixResp machineMatrix = biService.matrix(period, "machine");
        BiDtos.QuadrantResp quadrant = biService.quadrant(period);
        BiDtos.StockoutLossResp stockout = biService.stockoutLoss(period);

        List<AnalysisSection> sections = report.getSections();
        AnalysisSection overview = sectionOverview(pkg);
        sections.add(overview);
        sections.add(sectionSales(machineMatrix, quadrant));
        sections.add(sectionProfit(pkg.getProfit()));
        sections.add(sectionInventory(pkg, stockout));
        sections.add(sectionMoney(pkg));
        sections.add(sectionRisk(pkg));
        sections.add(sectionImprovement(pkg, quadrant, stockout));

        // ---- 一次 AI 起草综述(bizKey=month:period),规则草稿 = 概况节叙述,mock 原样回显 ----
        String execDraft = overview.getNarrative();
        StringBuilder promptData = new StringBuilder();
        for (AnalysisSection s : sections) {
            promptData.append("【").append(s.getTitle()).append("】").append(s.getNarrative()).append("\n");
        }
        String inputDigest = JSONUtil.toJsonStr(sections);
        BigDecimal confidence = confidenceOf(pkg);

        LlmGateway.GatewayResult result = llmGateway.invoke(LlmGateway.LlmTask.builder()
                .scene(AiScenes.MONTHLY)
                .sceneLabel("月报起草:" + period)
                .bizKey("month:" + period)
                .prompt("你是售卖机小生意的财务分析助理。以下七节数字全部由规则引擎算好(一个都不许改),"
                        + "请据此写一段面向老板的《" + period + " 月度经营分析报告》综述:先给整体结论,"
                        + "再点出最该关注的 2-3 件事,最后落到下月动作,口吻务实不套话。\n"
                        + "草稿:" + execDraft + "\n七节数据:\n" + promptData)
                .reasoning("七节数字来自:利润表(收入/毛利/利润)、资产快照(净家底)、BI 矩阵与四象限(销售结构)、"
                        + "损耗报表+BI缺货损失(库存)、现金流水+往来表(钱账)、库存查询+往来账龄(风险);"
                        + "LLM 仅起草综述文字,未参与任何计算。")
                .inputDigest(inputDigest)
                .confidence(confidence)
                // 真算:置信分=数据完备度(有销售+利润数 0.90 / 空月 0.40),据当月实际数据算出
                .confidenceSource("computed")
                .promptFingerprint("monthly-report-v1")
                .fallbackText(execDraft)
                .build(), force);

        report.setAiText(result.getCall().getOutputText());
        report.setLlmCallId(result.getCall().getId());
        report.setCacheHit(result.isCacheHit());
        report.setModel(result.getCall().getModel());
        report.setMock(result.getCall().getModel() != null && result.getCall().getModel().startsWith("mock"));
        return report;
    }

    // ============================== 七节 ==============================

    /** ① 经营概况 */
    private AnalysisSection sectionOverview(MonthlyDtos.PackageResp pkg) {
        FinReportDtos.ProfitResp p = pkg.getProfit();
        BigDecimal rev = rowAmount(p, "salesIncome");
        BigDecimal gross = rowAmount(p, "grossProfit");
        BigDecimal op = nz(p.getOperatingProfit());
        FinReportDtos.AssetSnapshotResp snap = pkg.getAsset().getSnapshot();
        BigDecimal netAsset = snap == null ? BigDecimal.ZERO : nz(snap.getNetAsset());

        AnalysisSection s = section("overview", "一、经营概况");
        s.getDataPoints().add(new DataPoint("销售收入", money(rev), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("毛利", money(gross), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("经营利润", money(op), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("净家底", money(netAsset), SRC_ASSET));

        String momText = "";
        if (snap != null && snap.getPrevNetAsset() != null) {
            BigDecimal delta = netAsset.subtract(snap.getPrevNetAsset());
            momText = String.format(",较 %s 净家底%s %s 元", snap.getPrevPeriod(),
                    delta.signum() >= 0 ? "增加" : "减少", money(delta.abs()));
        }
        s.setNarrative(String.format("%s 销售收入 %s 元,毛利 %s 元,经营利润 %s 元;截至目前净家底 %s 元%s。",
                pkg.getMonth(), money(rev), money(gross), money(op), money(netAsset), momText));
        return s;
    }

    /** ② 销售分析(机器 · 单品结构) */
    private AnalysisSection sectionSales(BiDtos.MatrixResp machineMatrix, BiDtos.QuadrantResp quadrant) {
        BigDecimal totalAmt = BigDecimal.ZERO;
        BiDtos.MatrixRow top = null;
        for (BiDtos.MatrixRow r : machineMatrix.getRows()) {
            BigDecimal amt = nz(r.getSalesAmt());
            totalAmt = totalAmt.add(amt);
            if (top == null || amt.compareTo(nz(top.getSalesAmt())) > 0) {
                top = r;
            }
        }
        Map<String, Integer> quad = new LinkedHashMap<>();
        for (BiDtos.QuadrantPoint pt : quadrant.getPoints()) {
            quad.merge(pt.getQuadrant() == null ? "未分类" : pt.getQuadrant(), 1, Integer::sum);
        }
        int star = quad.getOrDefault("明星", 0);
        int drain = quad.getOrDefault("引流", 0);
        int niche = quad.getOrDefault("利基", 0);
        int drop = quad.getOrDefault("淘汰", 0);

        AnalysisSection s = section("sales", "二、销售分析");
        s.getDataPoints().add(new DataPoint("机器销售合计", money(totalAmt), SRC_BI_MACHINE));
        s.getDataPoints().add(new DataPoint("销售最高机器",
                top == null ? "—" : top.getName() + "(" + money(nz(top.getSalesAmt())) + " 元)", SRC_BI_MACHINE));
        s.getDataPoints().add(new DataPoint("单品四象限",
                String.format("明星%d / 引流%d / 利基%d / 淘汰%d", star, drain, niche, drop), SRC_BI_QUADRANT));

        String topText = top == null ? "本月暂无机器销售数据" :
                String.format("销售最高的是「%s」%s 元", top.getName(), money(nz(top.getSalesAmt())));
        s.setNarrative(String.format("本月各机器销售合计 %s 元,%s。单品结构:明星品 %d 个、引流品 %d 个、"
                        + "利基品 %d 个、淘汰品 %d 个——建议多备明星、保住引流不断货,淘汰区尽快清仓腾货道。",
                money(totalAmt), topText, star, drain, niche, drop));
        return s;
    }

    /** ③ 利润分析 */
    private AnalysisSection sectionProfit(FinReportDtos.ProfitResp p) {
        BigDecimal rev = rowAmount(p, "salesIncome");
        BigDecimal gross = rowAmount(p, "grossProfit");
        BigDecimal fee = rowAmount(p, "platformFee");
        BigDecimal misc = rowAmount(p, "miscExpense");
        BigDecimal shrink = rowAmount(p, "shrinkage");
        BigDecimal op = nz(p.getOperatingProfit());
        BigDecimal marginPct = rev.signum() == 0 ? null :
                gross.multiply(BigDecimal.valueOf(100)).divide(rev, 1, RoundingMode.HALF_UP);

        AnalysisSection s = section("profit", "三、利润分析");
        s.getDataPoints().add(new DataPoint("毛利率", marginPct == null ? "—" : marginPct + "%", SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("平台手续费", money(fee.abs()), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("杂费", money(misc.abs()), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("损耗", money(shrink.abs()), SRC_PROFIT));
        s.getDataPoints().add(new DataPoint("经营利润", money(op), SRC_PROFIT));

        s.setNarrative(String.format("毛利率 %s,平台手续费吃掉 %s 元,杂费 %s 元,损耗 %s 元,"
                        + "最终经营利润 %s 元(= 毛利 − 手续费 − 杂费 − 损耗 ± 调整 + 其他收入,§13.1)。",
                marginPct == null ? "—(无收入)" : marginPct + "%",
                money(fee.abs()), money(misc.abs()), money(shrink.abs()), money(op)));
        return s;
    }

    /** ④ 库存与损耗 */
    private AnalysisSection sectionInventory(MonthlyDtos.PackageResp pkg, BiDtos.StockoutLossResp stockout) {
        ReportDtos.InventorySummaryResp inv = pkg.getInventory();
        BigDecimal closingAmt = inv.getTotal() == null ? BigDecimal.ZERO : nz(inv.getTotal().getClosingAmt());
        MonthlyDtos.LossSummary loss = pkg.getLoss();
        BigDecimal lossAmt = nz(loss.getTotalAmount());
        BigDecimal stockoutLoss = nz(stockout.getTotalEstLoss());

        AnalysisSection s = section("inventory", "四、库存与损耗");
        s.getDataPoints().add(new DataPoint("期末库存金额", money(closingAmt), SRC_INV));
        s.getDataPoints().add(new DataPoint("本月损耗合计", money(lossAmt), SRC_LOSS));
        s.getDataPoints().add(new DataPoint("损耗最大原因",
                loss.getTopReason() == null ? "—" : loss.getTopReason(), SRC_LOSS));
        s.getDataPoints().add(new DataPoint("缺货损失(估)", money(stockoutLoss), SRC_BI_STOCKOUT));

        String lossText = lossAmt.signum() == 0 ? "本月无损耗记录" :
                String.format("损耗合计 %s 元,最大头是「%s」", money(lossAmt), loss.getTopReason());
        s.setNarrative(String.format("期末库存金额 %s 元;%s;因缺货少赚约 %s 元(缺货时长×日均毛利估算)——"
                        + "建议优先治理最大损耗原因,并盯紧高缺货损失货道及时补货。",
                money(closingAmt), lossText, money(stockoutLoss)));
        return s;
    }

    /** ⑤ 钱账健康 */
    private AnalysisSection sectionMoney(MonthlyDtos.PackageResp pkg) {
        BigDecimal cash = nz(pkg.getCashflow().getMonthEndCashTotal());
        BigDecimal payable = nz(pkg.getPayable().getPayableTotal());
        int overdue = pkg.getPayable().getAging() == null ? 0 : pkg.getPayable().getAging().getOverdueCount();
        BigDecimal pending = pkg.getPayable().getPlatformPending() == null ? BigDecimal.ZERO
                : nz(pkg.getPayable().getPlatformPending().getPendingBalance());

        AnalysisSection s = section("money", "五、钱账健康");
        s.getDataPoints().add(new DataPoint("月末现金合计", money(cash), SRC_CASH));
        s.getDataPoints().add(new DataPoint("应付供应商合计", money(payable), SRC_PAYABLE));
        s.getDataPoints().add(new DataPoint("逾期供应商数", overdue + " 家", SRC_PAYABLE));
        s.getDataPoints().add(new DataPoint("平台待结算", money(pending), SRC_PAYABLE));

        s.setNarrative(String.format("月末账上现金 %s 元,欠供应商 %s 元(其中 %d 家已逾期),平台还有 %s 元没结回来。"
                        + "%s", money(cash), money(payable), overdue, money(pending),
                overdue > 0 ? "建议先清逾期欠款,避免影响供货。" : "钱账整体健康。"));
        return s;
    }

    /** ⑥ 异常与风险 */
    private AnalysisSection sectionRisk(MonthlyDtos.PackageResp pkg) {
        List<String> flags = new ArrayList<>();
        SettleAging aging = new SettleAging(pkg);
        if (aging.overdueCount > 0) {
            flags.add(String.format("逾期应付 %d 家(最长 %d 天)", aging.overdueCount, aging.maxOverdueDays));
        }
        boolean pendingOverdue = pkg.getPayable().getPlatformPending() != null
                && pkg.getPayable().getPlatformPending().isOverdue();
        if (pendingOverdue) {
            flags.add("平台待结算超期未回款");
        }
        int negCount = 0;
        for (ReportDtos.StockRow r : reportService.stock().getRows()) {
            if (r.isNegative()) {
                negCount++;
            }
        }
        if (negCount > 0) {
            flags.add(String.format("负库存 SKU %d 个(账实不符,先查原因)", negCount));
        }

        AnalysisSection s = section("risk", "六、异常与风险");
        s.getDataPoints().add(new DataPoint("逾期应付红灯", aging.overdueCount + " 家", SRC_PAYABLE));
        s.getDataPoints().add(new DataPoint("平台待结算超期", pendingOverdue ? "是" : "否", SRC_PAYABLE));
        s.getDataPoints().add(new DataPoint("负库存 SKU", negCount + " 个", SRC_STOCK));

        s.setNarrative(flags.isEmpty() ? "本月未发现重大风险红灯,各项指标在正常区间。"
                : "本月风险红灯:" + String.join(";", flags) + "。以上需逐项排查处置。");
        return s;
    }

    /** ⑦ 下月改进建议(规则据本月数据起草,采纳后入 PDCA 清单) */
    private AnalysisSection sectionImprovement(MonthlyDtos.PackageResp pkg, BiDtos.QuadrantResp quadrant,
                                               BiDtos.StockoutLossResp stockout) {
        List<String> tips = new ArrayList<>();
        MonthlyDtos.LossSummary loss = pkg.getLoss();
        if (loss.getTotalAmount().signum() > 0 && loss.getTopReason() != null) {
            tips.add(String.format("治理损耗:本月「%s」损耗最大(%s 元),下月定向排查并复盘是否收敛",
                    loss.getTopReason(), money(loss.getTotalAmount())));
        }
        if (nz(stockout.getTotalEstLoss()).signum() > 0) {
            tips.add(String.format("补货提效:缺货少赚约 %s 元,下月对高缺货货道加密补货频次",
                    money(stockout.getTotalEstLoss())));
        }
        long dropCount = quadrant.getPoints().stream().filter(p -> "淘汰".equals(p.getQuadrant())).count();
        if (dropCount > 0) {
            tips.add(String.format("清仓换新:%d 个淘汰品占着货道,下月清仓后换 BI 推荐新品", dropCount));
        }
        SettleAging aging = new SettleAging(pkg);
        if (aging.overdueCount > 0) {
            tips.add(String.format("清欠:%d 家供应商欠款逾期,下月优先安排付款", aging.overdueCount));
        }
        if (tips.isEmpty()) {
            tips.add("本月各项指标平稳,下月保持当前节奏,继续按 SOP 盘点与补货");
        }

        AnalysisSection s = section("improvement", "七、下月改进建议");
        int i = 1;
        for (String t : tips) {
            s.getDataPoints().add(new DataPoint("建议" + i, t, "规则据本月损耗/缺货/四象限/往来数据起草"));
            i++;
        }
        s.setNarrative("下月改进方向(采纳后自动入 PDCA 改进清单,到期回查):" + String.join(";", tips) + "。");
        return s;
    }

    // ============================== 工具 ==============================

    /** 应付账龄的两个数(逾期家数 / 最长天数),多处复用 */
    private static class SettleAging {
        final int overdueCount;
        final int maxOverdueDays;

        SettleAging(MonthlyDtos.PackageResp pkg) {
            if (pkg.getPayable().getAging() != null) {
                overdueCount = pkg.getPayable().getAging().getOverdueCount();
                maxOverdueDays = pkg.getPayable().getAging().getMaxOverdueDays();
            } else {
                overdueCount = 0;
                maxOverdueDays = 0;
            }
        }
    }

    private AnalysisSection section(String key, String title) {
        AnalysisSection s = new AnalysisSection();
        s.setKey(key);
        s.setTitle(title);
        return s;
    }

    /** 从利润表按行键取金额(带符号贡献值) */
    private BigDecimal rowAmount(FinReportDtos.ProfitResp p, String key) {
        if (p == null || p.getRows() == null) {
            return BigDecimal.ZERO;
        }
        return p.getRows().stream()
                .filter(r -> key.equals(r.getKey()))
                .map(r -> nz(r.getAmount()))
                .findFirst().orElse(BigDecimal.ZERO);
    }

    /** 置信分:有销售且有利润数 → 高;空月 → 低(真算,不写死) */
    private BigDecimal confidenceOf(MonthlyDtos.PackageResp pkg) {
        BigDecimal rev = rowAmount(pkg.getProfit(), "salesIncome");
        return rev.signum() > 0 ? new BigDecimal("0.90") : new BigDecimal("0.40");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String money(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
