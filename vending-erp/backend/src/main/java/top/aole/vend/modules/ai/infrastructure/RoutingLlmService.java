package top.aole.vend.modules.ai.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.modules.ai.domain.ILlmService;
import top.aole.vend.modules.ai.domain.LlmReply;

/**
 * LLM 自动路由(@Primary,业务方注入 ILlmService 拿到的就是它):
 * llm.* 配了真网关 → OpenAiCompatLlmService;没配 → MockLlmService(零外呼断路)。
 * 真 key 后一键启用 = 只需配 ENV,业务代码零改动。
 */
@Primary
@Service
@RequiredArgsConstructor
public class RoutingLlmService implements ILlmService {

    private final LlmProperties llmProperties;
    private final MockLlmService mockLlmService;
    private final OpenAiCompatLlmService openAiCompatLlmService;

    @Override
    public String chat(String prompt) {
        return delegate().chat(prompt);
    }

    @Override
    public LlmReply chat(String model, String prompt) {
        return delegate().chat(model, prompt);
    }

    private ILlmService delegate() {
        return llmProperties.isConfigured() ? openAiCompatLlmService : mockLlmService;
    }
}
