package top.aole.vend.modules.ai.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.domain.ILlmService;
import top.aole.vend.modules.ai.domain.LlmReply;
import top.aole.vend.modules.ai.domain.entity.LlmCallLog;
import top.aole.vend.modules.ai.mapper.LlmCallLogMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 统一 LLM 网关(withLlmIdempotent 风格 helper,全局 §4.19):
 *
 * 1. 24h idempotent:幂等键 = 接入点 + 业务key + 当日日期,当日已有成功/降级记录 → 直接复用,不重调不烧钱;
 *    force=true 例外(用户点"重新生成")。
 * 2. 多模型路由:模型名由 AiModelRouteService 按接入点解析(设置中心可改,不写死)。
 * 3. mock 断路:llm.* 未配置 → RoutingLlmService 自动走 MockLlmService,零外呼,log.model 记 "mock"。
 * 4. 透明四件套统一落 yc_vend_llm_call_log:reasoning(过程)/output(输出)/confidence(置信)/inputDigest(原始数据)。
 * 5. 失败降级:调用异常时若给了 fallbackText(规则草稿),输出草稿并记「降级」,业务不断。
 *
 * 铁律:LLM 永不算数——prompt 里的数字全部来自规则引擎,本网关只负责"把话说顺"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGateway {

    private final ILlmService llmService;
    private final LlmProperties llmProperties;
    private final AiModelRouteService routeService;
    private final LlmCallLogMapper llmCallLogMapper;

    /** 一次调用的输入(数字已由规则引擎算好) */
    @Data
    @Builder
    public static class LlmTask {
        /** 接入点(路由键) */
        private AiScenes scene;
        /** 细分场景名,落 log.scene(空则用接入点中文名) */
        private String sceneLabel;
        /** 业务幂等 key(不含日期,网关自动拼当日) */
        private String bizKey;
        private String prompt;
        /** 四件套·推理过程(规则引擎的计算说明) */
        private String reasoning;
        /** 四件套·原始数据(JSON) */
        private String inputDigest;
        /** 四件套·置信分(规则真算,如数据完备度) */
        private BigDecimal confidence;
        /** Prompt 模板指纹(版本追踪) */
        private String promptFingerprint;
        /** 失败降级文案(规则草稿);null = 失败直接抛 */
        private String fallbackText;
    }

    /** 调用结果:log 行 + 是否缓存命中 */
    @Value
    public static class GatewayResult {
        LlmCallLog call;
        boolean cacheHit;
    }

    /**
     * 幂等调用主入口。
     *
     * @param force true=跳过当日缓存强制重调(重新生成按钮)
     */
    public GatewayResult invoke(LlmTask task, boolean force) {
        String idemKey = idempotentKey(task.getScene(), task.getBizKey());
        if (!force) {
            LlmCallLog cached = findCached(idemKey);
            if (cached != null) {
                log.debug("[LlmGateway] 幂等命中 {} → 复用 call#{}", idemKey, cached.getId());
                return new GatewayResult(cached, true);
            }
        }

        boolean configured = llmProperties.isConfigured();
        String routedModel = routeService.resolve(task.getScene());
        long start = System.currentTimeMillis();

        String outputText;
        String callStatus = "成功";
        Integer tokensIn = null;
        Integer tokensOut = null;
        try {
            LlmReply reply = llmService.chat(routedModel, task.getPrompt());
            outputText = reply.getText();
            tokensIn = reply.getTokensIn();
            tokensOut = reply.getTokensOut();
        } catch (Exception ex) {
            if (task.getFallbackText() == null) {
                throw ex;
            }
            log.warn("[LlmGateway] {} 调用失败,降级用规则草稿:{}", task.getScene().getKey(), ex.getMessage());
            outputText = task.getFallbackText();
            callStatus = "降级";
        }

        LlmCallLog call = new LlmCallLog();
        call.setScene(StrUtil.blankToDefault(task.getSceneLabel(), task.getScene().getLabel()));
        // mock 断路时模型记 mock(路由目标留在括号里,接真后一眼可对照)
        call.setModel(configured ? routedModel : "mock(" + routedModel + ")");
        call.setPromptFingerprint(task.getPromptFingerprint());
        call.setIdempotentKey(idemKey);
        call.setCacheHit(0);
        call.setInputDigest(task.getInputDigest());
        call.setReasoning(task.getReasoning());
        call.setOutputText(outputText);
        call.setConfidence(task.getConfidence());
        call.setTokensIn(tokensIn);
        call.setTokensOut(tokensOut);
        call.setDurationMs((int) (System.currentTimeMillis() - start));
        call.setCallStatus(callStatus);
        llmCallLogMapper.insert(call);
        return new GatewayResult(call, false);
    }

    /**
     * 只落库不发起对话(供"调用已在别处发生"的场景,如 vision 识别):
     * 幂等规则与 invoke 完全一致;reply 为业务方已拿到的结果(mock 或真 vision)。
     */
    public GatewayResult record(LlmTask task, LlmReply reply, boolean force) {
        String idemKey = idempotentKey(task.getScene(), task.getBizKey());
        if (!force) {
            LlmCallLog cached = findCached(idemKey);
            if (cached != null) {
                return new GatewayResult(cached, true);
            }
        }
        boolean configured = llmProperties.isConfigured();
        String routedModel = routeService.resolve(task.getScene());
        LlmCallLog call = new LlmCallLog();
        call.setScene(StrUtil.blankToDefault(task.getSceneLabel(), task.getScene().getLabel()));
        call.setModel(configured ? routedModel : "mock(" + routedModel + ")");
        call.setPromptFingerprint(task.getPromptFingerprint());
        call.setIdempotentKey(idemKey);
        call.setCacheHit(0);
        call.setInputDigest(task.getInputDigest());
        call.setReasoning(task.getReasoning());
        call.setOutputText(reply.getText());
        call.setConfidence(task.getConfidence());
        call.setTokensIn(reply.getTokensIn());
        call.setTokensOut(reply.getTokensOut());
        call.setDurationMs(0);
        call.setCallStatus("成功");
        llmCallLogMapper.insert(call);
        return new GatewayResult(call, false);
    }

    /** 幂等键:接入点 + 业务key + 当日(24h 窗口) */
    public String idempotentKey(AiScenes scene, String bizKey) {
        return scene.getKey() + ":" + bizKey + ":" + LocalDate.now();
    }

    private LlmCallLog findCached(String idemKey) {
        return llmCallLogMapper.selectOne(new LambdaQueryWrapper<LlmCallLog>()
                .eq(LlmCallLog::getIdempotentKey, idemKey)
                .ne(LlmCallLog::getCallStatus, "失败")
                .orderByDesc(LlmCallLog::getId)
                .last("LIMIT 1"));
    }
}
