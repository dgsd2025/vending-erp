package top.aole.vend.modules.imports.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.service.LlmGateway;
import top.aole.vend.modules.imports.dto.ImportDtos.FixMapping;
import top.aole.vend.modules.imports.dto.ImportDtos.FixSuggestResp;
import top.aole.vend.modules.imports.service.ImportService.FixContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接入点#9 · 导入自愈(IMPORT_FIX)。
 *
 * 场景:厂家改了后台导出模板(列改名/换序),导入中心报"缺必填列"。
 * 做法(§4.11 站巨人上 + 铁律"LLM 永不算数"):
 *   1. 规则按表头字符相似度**先猜**一版映射(真活儿规则干);
 *   2. LLM 只**复核纠正**(且只允许选文件里真实存在的表头),mock/失败自动落回规则初猜;
 *   3. 用户在前端确认/微调后 applyFix 重新校验 → confirm 时按映射把表头改回期望名。
 * 全程走 LlmGateway:24h 幂等 + 透明四件套落 llm_call_log(前端🔬据 llmCallId 拉过程)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportFixService {

    private final ImportService importService;
    private final LlmGateway llmGateway;

    private static final String PROMPT_FP = "importfix-v1";

    public FixSuggestResp suggest(String token, boolean force) {
        FixContext ctx = importService.peekForFix(token);
        FixSuggestResp resp = new FixSuggestResp();
        resp.setToken(token);

        if (ctx.getMissingExpected().isEmpty()) {
            resp.setMode("无需修复(列都齐)");
            resp.setConfidence(BigDecimal.ONE);
            return resp;
        }

        // ① 规则模糊匹配打底:每个缺失期望列 → 最相似的实际表头
        List<FixMapping> mappings = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String expected : ctx.getMissingExpected()) {
            FixMapping m = new FixMapping();
            m.setExpected(expected);
            m.setRequired(isRequired(ctx, expected));
            List<String> ranked = rankCandidates(expected, ctx.getHeaders(), used);
            m.setCandidates(ranked);
            if (!ranked.isEmpty()) {
                m.setSuggested(ranked.get(0));
                used.add(ranked.get(0));
                m.setReason("表头「" + ranked.get(0) + "」与「" + expected + "」最接近");
            } else {
                m.setReason("没找到相近表头,请手动指定");
            }
            mappings.add(m);
        }

        // ② LLM 复核纠正(mock/失败 → 落回规则初猜)
        LlmGateway.GatewayResult r = llmGateway.invoke(LlmGateway.LlmTask.builder()
                .scene(AiScenes.IMPORT_FIX)
                .sceneLabel("导入自愈:" + ctx.getFileType())
                .bizKey(bizKey(ctx))
                .prompt(buildPrompt(ctx, mappings))
                .reasoning("规则按表头字符相似度初猜,LLM 复核纠正;只允许选文件里真实存在的表头。")
                .inputDigest(inputDigest(ctx))
                .confidence(computeConfidence(ctx, mappings))
                .confidenceSource("computed")
                .promptFingerprint(PROMPT_FP)
                .fallbackText(toRuleJson(mappings))
                .build(), force);

        applyLlmOverride(r.getCall().getOutputText(), mappings, ctx.getHeaders());

        resp.setMappings(mappings);
        resp.setLlmCallId(r.getCall().getId());
        resp.setMode(StrUtil.startWith(r.getCall().getModel(), "mock") ? "规则初猜(mock断路)" : "AI 复核");
        resp.setConfidence(computeConfidence(ctx, mappings));
        return resp;
    }

    // ---------------- helpers ----------------

    private boolean isRequired(FixContext ctx, String expected) {
        for (String[] col : ctx.getSpec()) {
            if (col[0].equals(expected)) {
                return "1".equals(col[1]);
            }
        }
        return false;
    }

    /** 按字符相似度给实际表头排序,排除已被占用的;取前 3 */
    private List<String> rankCandidates(String expected, List<String> headers, Set<String> used) {
        List<String> ranked = new ArrayList<>();
        headers.stream()
                .filter(h -> !used.contains(h))
                .map(h -> new Object[]{h, similarity(expected, h)})
                .filter(a -> (double) a[1] > 0.0)
                .sorted((a, b) -> Double.compare((double) b[1], (double) a[1]))
                .limit(3)
                .forEach(a -> ranked.add((String) a[0]));
        return ranked;
    }

    /** 相似度 = 共有不同字符数 / 较长串长度(中文单字友好) */
    private double similarity(String a, String b) {
        if (StrUtil.isBlank(a) || StrUtil.isBlank(b)) {
            return 0;
        }
        Set<Character> sa = new HashSet<>();
        for (char c : a.toCharArray()) sa.add(c);
        Set<Character> sb = new HashSet<>();
        for (char c : b.toCharArray()) sb.add(c);
        Set<Character> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        int max = Math.max(sa.size(), sb.size());
        return max == 0 ? 0 : (double) inter.size() / max;
    }

    private String buildPrompt(FixContext ctx, List<FixMapping> ruleGuess) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据导入的列映射助手。厂家改了导出模板,系统缺一些必填/可选列,需要把系统期望的列名对到文件里真实存在的表头。\n");
        sb.append("文件类型:").append(ctx.getFileType()).append("\n");
        sb.append("文件里真实存在的表头(只能从这里面选):").append(JSONUtil.toJsonStr(ctx.getHeaders())).append("\n");
        sb.append("缺失的期望列:").append(JSONUtil.toJsonStr(ctx.getMissingExpected())).append("\n");
        sb.append("样本数据行(帮你判断列含义):").append(JSONUtil.toJsonStr(ctx.getSampleRows())).append("\n");
        sb.append("规则初猜(供参考,可纠正):").append(toRuleJson(ruleGuess)).append("\n");
        sb.append("请只输出一个 JSON 对象,形如 {\"期望列名\":\"文件表头名\"};");
        sb.append("值必须是上面真实表头之一,判断不了就填 null;不要输出任何解释文字或代码块标记。");
        return sb.toString();
    }

    private String toRuleJson(List<FixMapping> mappings) {
        Map<String, String> m = new LinkedHashMap<>();
        for (FixMapping fm : mappings) {
            m.put(fm.getExpected(), fm.getSuggested());
        }
        return JSONUtil.toJsonStr(m);
    }

    private String inputDigest(FixContext ctx) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fileType", ctx.getFileType());
        d.put("headers", ctx.getHeaders());
        d.put("missing", ctx.getMissingExpected());
        return JSONUtil.toJsonStr(d);
    }

    private BigDecimal computeConfidence(FixContext ctx, List<FixMapping> mappings) {
        long reqMissing = mappings.stream().filter(FixMapping::isRequired).count();
        if (reqMissing == 0) {
            return BigDecimal.ONE;
        }
        long reqHit = mappings.stream()
                .filter(FixMapping::isRequired)
                .filter(m -> StrUtil.isNotBlank(m.getSuggested()))
                .count();
        return BigDecimal.valueOf(reqHit).divide(BigDecimal.valueOf(reqMissing), 2, RoundingMode.HALF_UP);
    }

    /** 解析 LLM 的 JSON 输出;只接受值∈真实表头的项,覆盖规则初猜。解析失败静默保留规则版 */
    private void applyLlmOverride(String output, List<FixMapping> mappings, List<String> headers) {
        if (StrUtil.isBlank(output)) {
            return;
        }
        String json = extractJson(output);
        if (json == null) {
            return;
        }
        JSONObject obj;
        try {
            obj = JSONUtil.parseObj(json);
        } catch (Exception e) {
            log.debug("[ImportFix] LLM 输出非 JSON,保留规则初猜:{}", StrUtil.brief(output, 80));
            return;
        }
        Set<String> headerSet = new HashSet<>(headers);
        for (FixMapping m : mappings) {
            if (!obj.containsKey(m.getExpected())) {
                continue;
            }
            String v = obj.getStr(m.getExpected());
            if (StrUtil.isNotBlank(v) && headerSet.contains(v)) {
                if (!v.equals(m.getSuggested())) {
                    m.setSuggested(v);
                    m.setReason("AI 复核:选定「" + v + "」");
                    if (!m.getCandidates().contains(v)) {
                        m.getCandidates().add(0, v);
                    }
                }
            }
        }
    }

    /** 从可能带代码块/前后缀的文本里抠出第一个 {...} */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private String bizKey(FixContext ctx) {
        List<String> sorted = new ArrayList<>(ctx.getHeaders());
        sorted.sort(String::compareTo);
        return ctx.getFileType() + "|" + String.join(",", sorted);
    }
}
