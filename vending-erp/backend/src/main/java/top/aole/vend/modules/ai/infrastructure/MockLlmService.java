package top.aole.vend.modules.ai.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.modules.ai.domain.ILlmService;

/**
 * LLM mock 实现:开发期全 mock,返回固定文案,绝不发真请求。
 * 上线前接真网关时另写实现类,按 LlmProperties.isConfigured() 切换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockLlmService implements ILlmService {

    private final LlmProperties llmProperties;

    @Override
    public String chat(String prompt) {
        log.info("[MockLlm] 收到 prompt(前 50 字): {}, 网关已配置={}",
                prompt == null ? "" : prompt.substring(0, Math.min(50, prompt.length())),
                llmProperties.isConfigured());
        return "[MOCK] 这是开发期占位回复,尚未接入真实 LLM 网关。";
    }
}
