package top.aole.vend.modules.ai.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.service.AiModelRouteService;
import top.aole.vend.modules.ai.service.AliasSuggestService;
import top.aole.vend.modules.ai.service.AnomalyService;
import top.aole.vend.modules.ai.service.CredentialOcrService;
import top.aole.vend.modules.ai.service.InsightService;
import top.aole.vend.modules.ai.service.LlmTransparencyService;
import top.aole.vend.modules.ai.service.NlQueryService;
import top.aole.vend.modules.basedata.interfaces.Operators;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 接入点全家桶统一接口(M4-5)。
 * 铁律:所有结论走 LlmGateway(mock 断路/24h idempotent/四件套落库);模型可切换不写死。
 */
@Api(tags = "AI · 接入点全家桶(mock 网关,真 key 后一键启用)")
@RestController
@RequestMapping("/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiModelRouteService routeService;
    private final AliasSuggestService aliasSuggestService;
    private final CredentialOcrService credentialOcrService;
    private final InsightService insightService;
    private final AnomalyService anomalyService;
    private final NlQueryService nlQueryService;
    private final LlmTransparencyService transparencyService;

    // ============================== 设置中心:AI 模型配置 ==============================

    @ApiOperation("模型路由表:12 接入点 + 默认模型 + 当前覆盖 + 期数/是否已挂接")
    @GetMapping("/models")
    public R<List<Map<String, Object>>> models() {
        Map<String, String> overrides = routeService.allOverrides();
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiScenes s : AiScenes.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", s.getKey());
            row.put("label", s.getLabel());
            row.put("description", s.getDescription());
            row.put("defaultModel", s.getDefaultModel());
            row.put("configuredModel", overrides.get(s.getKey()));
            row.put("effectiveModel", routeService.resolve(s));
            row.put("phase", s.getPhase());
            row.put("wired", s.isWired());
            list.add(row);
        }
        return R.ok(list);
    }

    @Data
    public static class SetModelReq {
        private String scene;
        private String model;
    }

    @ApiOperation("设置某接入点模型(空=清覆盖回默认;写 op_log)")
    @PutMapping("/models")
    public R<Void> setModel(@RequestBody SetModelReq req,
                            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        routeService.setModel(AiScenes.byKey(req.getScene()), req.getModel(), Operators.resolve(userName));
        return R.ok();
    }

    // ============================== #1 别名归集复核 ==============================

    @ApiOperation("#1 生成别名绑定建议(规则打分,写回 alias_pending.suggest/confidence/llm_call_id)")
    @PostMapping("/alias/suggest")
    public R<Map<String, Object>> aliasSuggest(@RequestParam(defaultValue = "false") boolean force) {
        return R.ok(aliasSuggestService.suggestAll(force));
    }

    // ============================== #2 凭证识别 ==============================

    @ApiOperation("#2 识别凭证 → 金额/日期/对方(预填表单可人工改;写 attachment.ocr_json)")
    @PostMapping("/ocr/{attachmentId}")
    public R<Object> ocr(@PathVariable Long attachmentId,
                         @RequestParam(defaultValue = "false") boolean force) {
        return R.ok(credentialOcrService.recognize(attachmentId, force));
    }

    // ============================== #3 解释层扩点 ==============================

    @ApiOperation("#3 经营解读(subScene=stocktake-diff/settlement-diff/asset-mom)")
    @GetMapping("/insight/{subScene}")
    public R<Map<String, Object>> insight(@PathVariable String subScene,
                                          @RequestParam(defaultValue = "false") boolean force) {
        return R.ok(insightService.generate(subScene, force));
    }

    // ============================== #4 异常侦探 ==============================

    @ApiOperation("#4 异常清单(纯规则侦测:销量骤变/盘差/结算差异)")
    @GetMapping("/anomalies")
    public R<List<AnomalyService.Anomaly>> anomalies() {
        return R.ok(anomalyService.detect());
    }

    @ApiOperation("#4 给一条异常挂 LLM 归因叙事(mock)")
    @PostMapping("/anomalies/explain")
    public R<Map<String, Object>> anomalyExplain(@RequestBody AnomalyService.Anomaly anomaly,
                                                 @RequestParam(defaultValue = "false") boolean force) {
        return R.ok(anomalyService.explain(anomaly, force));
    }

    // ============================== #6 NL 查数 ==============================

    @ApiOperation("#6 自然语言查数(只读白名单视图+LIMIT;实验性)")
    @GetMapping("/nl-query")
    public R<NlQueryService.QueryResult> nlQuery(@RequestParam String q,
                                                 @RequestParam(defaultValue = "false") boolean force) {
        return R.ok(nlQueryService.ask(q, force));
    }

    @ApiOperation("#6 常见问题建议(前端 chips)")
    @GetMapping("/nl-query/suggestions")
    public R<List<String>> nlSuggestions() {
        return R.ok(nlQueryService.suggestions());
    }

    // ============================== 透明四件套 ==============================

    @ApiOperation("🔬 AI 过程四件套(推理/输出/置信/原始数据),前端 LlmTransparencyBadge 用")
    @GetMapping("/transparency/{llmCallId}")
    public R<Map<String, Object>> transparency(@PathVariable Long llmCallId) {
        return R.ok(transparencyService.detail(llmCallId));
    }
}
