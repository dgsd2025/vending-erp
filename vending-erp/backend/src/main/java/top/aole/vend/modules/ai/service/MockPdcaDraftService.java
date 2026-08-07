package top.aole.vend.modules.ai.service;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.domain.IPdcaDraftService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 接入点#7 PDCA 建议起草 mock 实现(pdca 票注入 IPdcaDraftService 即用)。
 *
 * mock:按指标名套模板生成"措施 + 验证指标"(数字取入参的 target,不自己算);走 LlmGateway 落四件套 + idempotent。
 * 真 key:同一 LlmTask.prompt 直接由 Qwen-Plus 生成更贴合的措施——业务方零改动(网关自动路由)。
 */
@Service
@RequiredArgsConstructor
public class MockPdcaDraftService implements IPdcaDraftService {

    private final LlmGateway llmGateway;

    @Override
    public PdcaDraftResult draft(PdcaDraftReq req) {
        String metric = req.getMetricName() == null ? "该指标" : req.getMetricName();
        BigDecimal target = req.getTargetValue();

        String measureTitle = String.format("[MOCK] 改进「%s」:%s", metric,
                req.getScope() == null ? "" : req.getScope());
        String measureDetail = String.format(
                "%s 当前 %s,未达目标 %s。建议:①定位主因(先查数据口径再查现场);②针对性动作(调整补货上限/清理慢销/治理损耗原因);"
                        + "③到期回查是否收敛。(mock 占位,接真网关后由 Qwen-Plus 按现场数据细化)",
                metric, req.getActualValue(), target);

        List<VerifyMetric> metrics = new ArrayList<>();
        metrics.add(VerifyMetric.builder()
                .name(metric)
                .target(target)
                .note("到期回查:应达到或优于目标值").build());

        String draft = measureTitle + " —— " + measureDetail;
        JSONObject digest = new JSONObject()
                .set("metricName", metric)
                .set("actualValue", req.getActualValue())
                .set("targetValue", target)
                .set("scope", req.getScope())
                .set("context", req.getContext());

        LlmGateway.GatewayResult result = llmGateway.invoke(LlmGateway.LlmTask.builder()
                .scene(AiScenes.PDCA)
                .sceneLabel("PDCA起草:" + metric)
                .bizKey(req.getBizKey() == null ? "adhoc:" + metric : req.getBizKey())
                .prompt("你是精益改进顾问。以下 C 指标红灯由规则引擎判定(数字不许改),"
                        + "请起草一条改进措施 + 可回查的验证指标。\n草稿:" + draft + "\n红灯数据:" + digest)
                .reasoning("pdca 规则引擎判红灯(LLM 未参与判定):" + metric + " 实际 "
                        + req.getActualValue() + " vs 目标 " + target)
                .inputDigest(digest.toString())
                // 诚实标注:mock 起草是套模板文案,置信给固定基准档 0.80,不号称真算(规则未覆盖措施质量)
                .confidence(new BigDecimal("0.80"))
                .confidenceSource("fixed")
                .promptFingerprint("pdca-draft-v1")
                .fallbackText(draft)
                .build(), req.isForce());

        return PdcaDraftResult.builder()
                .measureTitle(measureTitle)
                .measureDetail(result.getCall().getOutputText())
                .verifyMetrics(metrics)
                .suggestDueDays(14)
                .llmCallId(result.getCall().getId())
                .cacheHit(result.isCacheHit())
                .model(result.getCall().getModel())
                .build();
    }
}
