package top.aole.vend.modules.ai.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.AiScenes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入点#4:异常侦探。
 *
 * 分工铁死:异常清单由【规则引擎】侦测(SQL 聚合 + 阈值,LLM 零参与);
 * 每条异常可挂【LLM 归因叙事】(mock:把规则算出的数字说成故事;真 key 后自动升级)。
 *
 * 三类规则(§12.1 #4 定义的数据源):
 * 1. 销量骤变:机器×商品 近7天 vs 前7天 销量环比 ±50%(补货口径=正常+兑换;前7天≥5件才有资格,防小样本)
 * 2. 盘差超阈:近30天已完成盘点单,差异金额合计 |Σ| > ¥50(与盘点老板确认阈值同一把尺子)
 * 3. 结算差异:结算单 差异挂起 或 |差异①|+|差异②| > ¥10
 */
@Service
@RequiredArgsConstructor
public class AnomalyService {

    /** 销量环比阈值 ±50% */
    private static final BigDecimal SALES_SWING = new BigDecimal("0.5");
    /** 前周销量低于此件数不侦测(小样本噪音) */
    private static final BigDecimal MIN_BASE_QTY = new BigDecimal("5");
    /** 盘差阈值 ¥50(同盘点老板确认线) */
    private static final BigDecimal STOCKTAKE_THRESHOLD = new BigDecimal("50");
    /** 结算差异阈值 ¥10 */
    private static final BigDecimal SETTLE_THRESHOLD = new BigDecimal("10");

    private final JdbcTemplate jdbcTemplate;
    private final LlmGateway llmGateway;

    @Data
    public static class Anomaly {
        /** 稳定键(归因叙事 idempotent 用):type:业务标识 */
        private String key;
        /** sales-swing / stocktake-diff / settle-diff */
        private String type;
        private String title;
        /** 严重度:红/黄 */
        private String severity;
        /** 置信分(规则真算:按偏离阈值的程度算,越离谱越高;非固定档) */
        private BigDecimal confidence;
        /** 规则算出的数字明细(前端表格 + LLM 原始数据) */
        private Map<String, Object> metrics = new LinkedHashMap<>();
    }

    /** 侦测异常清单(纯规则,现算现返,不落表) */
    public List<Anomaly> detect() {
        List<Anomaly> list = new ArrayList<>();
        detectSalesSwing(list);
        detectStocktakeDiff(list);
        detectSettleDiff(list);
        return list;
    }

    /**
     * 归因叙事入口(P1-2 安全修复):只收 key,服务端重新 detect() 定位那条异常,
     * 用**服务端算出的 metrics** 起草——不吃客户端传来的数字/幂等键,杜绝伪造数字与缓存投毒。
     */
    public Map<String, Object> explainByKey(String key, boolean force) {
        if (StrUtil.isBlank(key)) {
            throw new BizException("缺少异常 key,先调 GET /v1/ai/anomalies 拿清单再选一条");
        }
        Anomaly found = detect().stream()
                .filter(a -> key.equals(a.getKey()))
                .findFirst()
                .orElseThrow(() -> new BizException("异常不存在或已消解:" + key + "(请刷新异常清单后重试)"));
        return explain(found, force);
    }

    /**
     * 给一条异常挂归因叙事(LLM mock;idempotent key = 异常key+当日)。
     * anomaly 必须来自服务端 {@link #detect()}(数字为规则引擎所算);对外入口一律走 {@link #explainByKey}。
     */
    public Map<String, Object> explain(Anomaly anomaly, boolean force) {
        if (anomaly == null || anomaly.getKey() == null) {
            throw new BizException("缺少异常明细(key/metrics),先调 GET /v1/ai/anomalies 拿清单");
        }
        String draft = draftOf(anomaly);
        LlmGateway.GatewayResult result = llmGateway.invoke(LlmGateway.LlmTask.builder()
                .scene(AiScenes.ANOMALY)
                .sceneLabel("异常侦探:" + anomaly.getType())
                .bizKey(anomaly.getKey())
                .prompt("你是售卖机经营异常侦探。规则引擎已侦测出以下异常(数字不许改),"
                        + "请给一段两三句的归因叙事:最可能的原因 + 建议动作。\n草稿:" + draft
                        + "\n异常明细:" + new JSONObject(anomaly.getMetrics()))
                .reasoning("规则侦测过程(LLM 未参与):" + anomaly.getTitle() + ";阈值与口径见 AnomalyService 注释。")
                .inputDigest(new JSONObject(anomaly.getMetrics()).toString())
                // 真算:置信=规则偏离度(超阈越多越高),侦测时算好挂在 anomaly.confidence
                .confidence(anomaly.getConfidence() == null ? new BigDecimal("0.85") : anomaly.getConfidence())
                .confidenceSource("computed")
                .promptFingerprint("anomaly-explain-v1")
                .fallbackText(draft)
                .build(), force);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("key", anomaly.getKey());
        resp.put("text", result.getCall().getOutputText());
        resp.put("llmCallId", result.getCall().getId());
        resp.put("cacheHit", result.isCacheHit());
        return resp;
    }

    // ============================== 规则 1:销量骤变 ==============================

    private void detectSalesSwing(List<Anomaly> out) {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);
        LocalDate twoWeeksAgo = today.minusDays(14);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(m.machine_name,'未知机器') AS machineName,"
                        + " COALESCE(p.product_name, s.alias_name_raw, '未归集商品') AS productName,"
                        + " SUM(CASE WHEN s.biz_time >= ? THEN s.qty ELSE 0 END) AS curQty,"
                        + " SUM(CASE WHEN s.biz_time < ?  THEN s.qty ELSE 0 END) AS prevQty,"
                        + " s.machine_id AS machineId, s.product_id AS productId"
                        + " FROM yc_vend_sale_record s"
                        + " LEFT JOIN yc_vend_machine m ON m.id = s.machine_id"
                        + " LEFT JOIN yc_vend_product p ON p.id = s.product_id"
                        + " WHERE s.is_deleted=0 AND s.order_type IN ('正常','兑换')"
                        + " AND s.biz_time >= ?"
                        + " GROUP BY s.machine_id, s.product_id, machineName, productName",
                weekAgo, weekAgo, twoWeeksAgo);
        for (Map<String, Object> r : rows) {
            BigDecimal cur = new BigDecimal(r.get("curQty").toString());
            BigDecimal prev = new BigDecimal(r.get("prevQty").toString());
            if (prev.compareTo(MIN_BASE_QTY) < 0) {
                continue; // 小样本不侦测
            }
            BigDecimal change = cur.subtract(prev).divide(prev, 4, RoundingMode.HALF_UP);
            if (change.abs().compareTo(SALES_SWING) < 0) {
                continue;
            }
            Anomaly a = new Anomaly();
            a.setType("sales-swing");
            a.setKey("sales-swing:" + r.get("machineId") + ":" + r.get("productId"));
            int pct = change.multiply(new BigDecimal(100)).intValue();
            a.setTitle(String.format("%s「%s」销量周环比 %+d%%(前7天 %s 件 → 近7天 %s 件)",
                    r.get("machineName"), r.get("productName"), pct,
                    prev.stripTrailingZeros().toPlainString(), cur.stripTrailingZeros().toPlainString()));
            a.setSeverity(change.compareTo(BigDecimal.ZERO) < 0 ? "红" : "黄");
            // 真算置信:环比偏离越大越确信是真异常(±50% 阈值起步,封顶 0.99)
            a.setConfidence(deviationConfidence(change.abs()));
            a.getMetrics().put("machineName", r.get("machineName"));
            a.getMetrics().put("productName", r.get("productName"));
            a.getMetrics().put("prev7dQty", prev);
            a.getMetrics().put("cur7dQty", cur);
            a.getMetrics().put("changePct", pct);
            a.getMetrics().put("口径", "补货口径=正常+兑换;近7天 vs 前7天");
            out.add(a);
        }
    }

    // ============================== 规则 2:盘差超阈 ==============================

    private void detectStocktakeDiff(List<Anomaly> out) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT st.id AS stId, st.st_no AS stNo, st.scope_type AS scopeType,"
                        + " COALESCE(m.machine_name,'仓库') AS place,"
                        + " SUM(COALESCE(i.diff_amount,0)) AS diffAmount,"
                        + " SUM(CASE WHEN i.diff_qty<>0 THEN 1 ELSE 0 END) AS diffLines"
                        + " FROM yc_vend_stocktake st"
                        + " JOIN yc_vend_stocktake_item i ON i.stocktake_id = st.id AND i.is_deleted=0"
                        + " LEFT JOIN yc_vend_machine m ON m.id = st.machine_id"
                        + " WHERE st.is_deleted=0 AND st.st_status='已完成'"
                        + " AND st.snapshot_time >= ? AND i.offline_exempt=0"
                        + " GROUP BY st.id, st.st_no, st.scope_type, place"
                        + " HAVING ABS(SUM(COALESCE(i.diff_amount,0))) > ?",
                LocalDate.now().minusDays(30), STOCKTAKE_THRESHOLD);
        for (Map<String, Object> r : rows) {
            BigDecimal amt = new BigDecimal(r.get("diffAmount").toString());
            Anomaly a = new Anomaly();
            a.setType("stocktake-diff");
            a.setKey("stocktake-diff:" + r.get("stId"));
            a.setTitle(String.format("盘点单 %s(%s)差异金额 %.2f 元,超 ±%.0f 阈值(%s 行有差异)",
                    r.get("stNo"), r.get("place"), amt, STOCKTAKE_THRESHOLD, r.get("diffLines")));
            a.setSeverity("红");
            // 真算置信:盘差金额超 ¥50 阈值越多越确信
            a.setConfidence(exceedConfidence(amt.abs(), STOCKTAKE_THRESHOLD));
            a.getMetrics().putAll(r);
            a.getMetrics().put("threshold", STOCKTAKE_THRESHOLD);
            out.add(a);
        }
    }

    // ============================== 规则 3:结算差异 ==============================

    private void detectSettleDiff(List<Anomaly> out) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, stmt_no AS stmtNo, period_start AS periodStart, period_end AS periodEnd,"
                        + " diff_sales AS diffSales, diff_arrival AS diffArrival, stl_status AS stlStatus"
                        + " FROM yc_vend_settlement"
                        + " WHERE is_deleted=0"
                        + " AND (stl_status='差异挂起' OR ABS(diff_sales)+ABS(diff_arrival) > ?)"
                        + " AND period_end >= ?",
                SETTLE_THRESHOLD, LocalDate.now().minusDays(90));
        for (Map<String, Object> r : rows) {
            Anomaly a = new Anomaly();
            a.setType("settle-diff");
            a.setKey("settle-diff:" + r.get("id"));
            a.setTitle(String.format("结算单 %s 差异:销售差 %s 元 / 到账差 %s 元(状态:%s)",
                    r.get("stmtNo"), r.get("diffSales"), r.get("diffArrival"), r.get("stlStatus")));
            boolean suspended = "差异挂起".equals(r.get("stlStatus"));
            a.setSeverity(suspended ? "红" : "黄");
            // 真算置信:差异挂起=已被规则明确标红,给高置信 0.90;否则按两差合计超 ¥10 阈值的程度算
            BigDecimal diffSum = absOf(r.get("diffSales")).add(absOf(r.get("diffArrival")));
            a.setConfidence(suspended ? new BigDecimal("0.90") : exceedConfidence(diffSum, SETTLE_THRESHOLD));
            a.getMetrics().putAll(r);
            a.getMetrics().put("口径", "差异①=系统销售额−平台账单;差异②=预计到账−实际到账(§7.3)");
            out.add(a);
        }
    }

    // ============================== 置信分真算(偏离度) ==============================

    /** 比率偏离度→置信:dev 为偏离比率(如 0.7 = 偏离 70%),夹到 [0.50, 0.99] */
    private static BigDecimal deviationConfidence(BigDecimal dev) {
        return dev.min(new BigDecimal("0.99")).max(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP);
    }

    /** 金额超阈度→置信:magnitude 越超过 threshold 越高;conf = 0.5 + 0.5*(1 − threshold/magnitude),封顶 0.99 */
    private static BigDecimal exceedConfidence(BigDecimal magnitude, BigDecimal threshold) {
        if (magnitude == null || magnitude.signum() <= 0 || threshold == null || threshold.signum() <= 0) {
            return new BigDecimal("0.60");
        }
        BigDecimal ratio = threshold.divide(magnitude, 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        BigDecimal conf = new BigDecimal("0.50")
                .add(new BigDecimal("0.50").multiply(BigDecimal.ONE.subtract(ratio)));
        return conf.min(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
    }

    /** 安全取绝对值(字段可能为 null / 非数字) */
    private static BigDecimal absOf(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(v.toString()).abs();
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** 归因叙事草稿(规则数字拼装,mock 直接回显) */
    private String draftOf(Anomaly a) {
        switch (a.getType()) {
            case "sales-swing":
                Object pct = a.getMetrics().get("changePct");
                boolean drop = pct != null && Integer.parseInt(pct.toString()) < 0;
                return a.getTitle() + (drop
                        ? " 同期若他机正常,优先怀疑卡货/缺货/货道故障,建议到机检查并盘一次该货道。"
                        : " 上升异常多为兑换活动或补货到位,确认是否需要加大备货。");
            case "stocktake-diff":
                return a.getTitle() + " 建议走盘点五步向导逐行归因(先查漏导入/录错,再谈吞货被盗),差异行必须选原因。";
            case "settle-diff":
                return a.getTitle() + " 先核对平台账单区间与系统口径(仅正常单,退款为负),两差处理完才可核销。";
            default:
                return a.getTitle();
        }
    }
}
