package top.aole.vend.modules.ai.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.service.AssetSnapshotService;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入点#3:解释层扩点(补货已有,见 ReplenishEngine.attachExplain 样板)。
 * 本票新挂 3 个位:盘点差异汇总 / 结算两差 / 资产快照环比。
 *
 * 铁律:数字全部由既有规则口径出(lossStats / 结算表聚合 / 资产快照),LLM(mock)只把
 * 草稿说成人话;四件套落 llm_call_log;24h idempotent(key=场景+当日)。
 * 置信分=数据完备度真算(有数据 0.9 / 空数据 0.3),不拍脑袋。
 */
@Service
@RequiredArgsConstructor
public class InsightService {

    public static final String SCENE_STOCKTAKE = "stocktake-diff";
    public static final String SCENE_SETTLEMENT = "settlement-diff";
    public static final String SCENE_ASSET = "asset-mom";

    private final StocktakeService stocktakeService;
    private final AssetSnapshotService assetSnapshotService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final LlmGateway llmGateway;

    /** 生成某个挂点的 AI 解读(四件套 + idempotent) */
    public Map<String, Object> generate(String subScene, boolean force) {
        Digest digest;
        switch (subScene) {
            case SCENE_STOCKTAKE:
                digest = stocktakeDigest();
                break;
            case SCENE_SETTLEMENT:
                digest = settlementDigest();
                break;
            case SCENE_ASSET:
                digest = assetDigest();
                break;
            default:
                throw new BizException("未知解读挂点:" + subScene
                        + "(可选:" + SCENE_STOCKTAKE + "/" + SCENE_SETTLEMENT + "/" + SCENE_ASSET + ")");
        }

        LlmGateway.GatewayResult result = llmGateway.invoke(LlmGateway.LlmTask.builder()
                .scene(AiScenes.EXPLAIN)
                .sceneLabel("解释层:" + digest.title)
                .bizKey(subScene)
                .prompt("你是售卖机小生意的经营解读助理。以下数字全部由规则引擎算好(不许改),"
                        + "请用两三句大白话向老板解读,先说结论再说该干什么。\n草稿:" + digest.draft
                        + "\n数据:" + digest.dataJson)
                .reasoning(digest.reasoning)
                .inputDigest(digest.dataJson)
                .confidence(digest.confidence)
                .promptFingerprint("insight-" + subScene + "-v1")
                .fallbackText(digest.draft)
                .build(), force);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("subScene", subScene);
        resp.put("title", digest.title);
        resp.put("text", result.getCall().getOutputText());
        resp.put("llmCallId", result.getCall().getId());
        resp.put("cacheHit", result.isCacheHit());
        resp.put("model", result.getCall().getModel());
        return resp;
    }

    // ============================== 三个挂点的规则摘要 ==============================

    private static class Digest {
        String title;
        String draft;
        String reasoning;
        String dataJson;
        BigDecimal confidence;
    }

    /** 盘点差异汇总:近 3 个月损耗按原因(与 loss-stats 报表同口径) */
    private Digest stocktakeDigest() {
        List<StocktakeDtos.LossStatRow> rows = stocktakeService.lossStats(3);
        Digest d = new Digest();
        d.title = "盘点差异汇总";
        d.dataJson = JSONUtil.toJsonStr(rows);
        d.reasoning = "规则口径:近3个月已完成盘点的盘亏行,按 原因×月份 汇总(线下豁免行不算损耗);"
                + "数据来自 loss-stats 报表同一取数口,LLM 未参与算数。";
        if (rows.isEmpty()) {
            d.draft = "近 3 个月没有盘点差异记录——要么真没损耗,要么还没做盘点,建议按月度 SOP 盘一轮。";
            d.confidence = new BigDecimal("0.30");
            return d;
        }
        BigDecimal totalAmount = rows.stream()
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        StocktakeDtos.LossStatRow top = rows.stream()
                .max((a, b) -> nz(a.getAmount()).compareTo(nz(b.getAmount())))
                .orElse(rows.get(0));
        d.draft = String.format("近 3 个月盘点损耗合计 %.2f 元;最大头是「%s」(%.2f 元,%d 行)。"
                        + "建议优先治理该原因,并在下轮盘点验证是否收敛。",
                totalAmount, top.getReason(), nz(top.getAmount()), top.getItemCount());
        d.confidence = new BigDecimal("0.90");
        return d;
    }

    /** 结算两差:近 90 天结算单 差异①(销售差)/差异②(到账差)聚合 + 挂起单数 */
    private Digest settlementDigest() {
        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS bills,"
                        + " COALESCE(SUM(ABS(diff_sales)),0)   AS totalDiffSales,"
                        + " COALESCE(SUM(ABS(diff_arrival)),0) AS totalDiffArrival,"
                        + " SUM(CASE WHEN stl_status='差异挂起' THEN 1 ELSE 0 END) AS suspended"
                        + " FROM yc_vend_settlement"
                        + " WHERE is_deleted=0 AND period_end >= ?", LocalDate.now().minusDays(90));
        Digest d = new Digest();
        d.title = "结算两差对账";
        d.dataJson = new JSONObject(agg).toString();
        d.reasoning = "规则口径(§7.3):差异①=系统销售额−平台账单额(差=漏单/吞货);差异②=预计到账−实际到账"
                + "(差=费率变化/额外扣款);近90天聚合,LLM 未参与算数。";
        long bills = ((Number) agg.get("bills")).longValue();
        if (bills == 0) {
            d.draft = "近 90 天还没有平台结算单——结算模式定型并导入平台账单后,这里会给两差对账解读。";
            d.confidence = new BigDecimal("0.30");
            return d;
        }
        BigDecimal ds = new BigDecimal(agg.get("totalDiffSales").toString());
        BigDecimal da = new BigDecimal(agg.get("totalDiffArrival").toString());
        long suspended = ((Number) agg.get("suspended")).longValue();
        d.draft = String.format("近 90 天 %d 张结算单:销售差异累计 %.2f 元,到账差异累计 %.2f 元,%d 张差异挂起。%s",
                bills, ds, da, suspended,
                suspended > 0 ? "先处理挂起单——差异挂着不清,月底利润表就是糊的。"
                        : (ds.add(da).compareTo(BigDecimal.ZERO) == 0 ? "两差全平,账很干净。" : "差异不大,核对后按差异处理流程销掉即可。"));
        d.confidence = new BigDecimal("0.90");
        return d;
    }

    /** 资产快照环比:最近两期净流动资产对比(§13.1 公式) */
    private Digest assetDigest() {
        List<FinReportDtos.SnapshotRow> trend = assetSnapshotService.trend(6);
        Digest d = new Digest();
        d.title = "资产快照环比";
        d.dataJson = JSONUtil.toJsonStr(trend);
        d.reasoning = "规则口径(§13.1 效力最高):净流动资产=库存+平台待结算+现金+索赔应收−应付;"
                + "取最近两期月度快照做环比,LLM 未参与算数。";
        if (trend.size() < 2) {
            d.draft = "资产快照不足两期,还比不了环比——先在报表页归档本月快照。";
            d.confidence = new BigDecimal("0.30");
            return d;
        }
        FinReportDtos.SnapshotRow cur = trend.get(trend.size() - 1);
        FinReportDtos.SnapshotRow prev = trend.get(trend.size() - 2);
        BigDecimal delta = nz(cur.getNetAsset()).subtract(nz(prev.getNetAsset()));
        String pct = nz(prev.getNetAsset()).compareTo(BigDecimal.ZERO) == 0 ? "—"
                : delta.multiply(new BigDecimal(100))
                        .divide(nz(prev.getNetAsset()).abs(), 1, RoundingMode.HALF_UP) + "%";
        d.draft = String.format("%s 净家底 %.2f 元,比 %s(%.2f 元)%s %.2f 元(%s)。"
                        + "拆开看:库存 %.2f / 待结算 %.2f / 现金 %.2f / 应付 %.2f——%s",
                cur.getPeriod(), nz(cur.getNetAsset()), prev.getPeriod(), nz(prev.getNetAsset()),
                delta.compareTo(BigDecimal.ZERO) >= 0 ? "多了" : "少了", delta.abs(), pct,
                nz(cur.getInventoryAmount()), nz(cur.getPlatformPending()), nz(cur.getCashTotal()),
                nz(cur.getPayableTotal()),
                delta.compareTo(BigDecimal.ZERO) >= 0 ? "家底在变厚,方向对。" : "家底在变薄,看看是压货了还是应付涨了。");
        d.confidence = new BigDecimal("0.90");
        return d;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
