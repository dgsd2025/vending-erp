package top.aole.vend.modules.ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.entity.LlmCallLog;
import top.aole.vend.modules.ai.mapper.LlmCallLogMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 透明四件套查询(🔬 过程弹窗数据源,全局 §4.7 铁律)。
 * 前端 LlmTransparencyBadge 点开 → 拿一条 llm_call_log 的四 Tab:
 * 推理过程 / 完整输出 / 确信分构成 / 原始数据。
 */
@Service
@RequiredArgsConstructor
public class LlmTransparencyService {

    private final LlmCallLogMapper llmCallLogMapper;

    /** 单条 AI 调用的四件套(前端弹窗直接渲染) */
    public Map<String, Object> detail(Long llmCallId) {
        LlmCallLog log = llmCallLogMapper.selectById(llmCallId);
        if (log == null) {
            throw new BizException("AI 调用记录不存在:id=" + llmCallId);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", log.getId());
        resp.put("scene", log.getScene());
        resp.put("model", log.getModel());
        resp.put("promptFingerprint", log.getPromptFingerprint());
        resp.put("callStatus", log.getCallStatus());
        resp.put("cacheHit", log.getCacheHit() != null && log.getCacheHit() == 1);
        resp.put("createTime", log.getCreateTime());
        // 四件套
        resp.put("reasoning", log.getReasoning());        // ① 推理过程
        resp.put("outputText", log.getOutputText());      // ② 完整输出
        resp.put("confidence", log.getConfidence());      // ③ 确信分
        // ③ 确信分来源:computed=规则真算 / fixed=固定基准置信(规则未覆盖)——前端 Tab③ 据此显示口径,不再一律号称"真算"
        resp.put("confidenceSource", log.getConfidenceSource() == null ? "fixed" : log.getConfidenceSource());
        resp.put("inputDigest", log.getInputDigest());    // ④ 原始数据
        // 成本透明
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("tokensIn", log.getTokensIn());
        cost.put("tokensOut", log.getTokensOut());
        cost.put("costAmount", log.getCostAmount());
        cost.put("durationMs", log.getDurationMs());
        resp.put("cost", cost);
        return resp;
    }
}
