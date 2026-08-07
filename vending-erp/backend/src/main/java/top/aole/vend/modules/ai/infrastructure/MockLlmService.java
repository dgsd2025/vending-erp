package top.aole.vend.modules.ai.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.ai.domain.ILlmService;
import top.aole.vend.modules.ai.domain.LlmReply;

/**
 * LLM mock 实现:无 key 断路档,返回带 [MOCK] 前缀的合理文案,绝不发网络请求。
 * 约定:prompt 内含「草稿:xxx」行时,直接润色回显该草稿(规则出数字,mock 只是把草稿当解释还回来)——
 * 这样 mock 阶段 UI 上已经是真数字的人话,接真网关只是把话说得更顺。
 */
@Slf4j
@Service("mockLlmService")
public class MockLlmService implements ILlmService {

    /** 草稿标记:规则引擎先写好一句话草稿,mock 原样回显 */
    private static final String DRAFT_MARK = "草稿:";

    @Override
    public String chat(String prompt) {
        return chat(null, prompt).getText();
    }

    @Override
    public LlmReply chat(String model, String prompt) {
        log.debug("[MockLlm] 收到 prompt(前 50 字): {}",
                prompt == null ? "" : prompt.substring(0, Math.min(50, prompt.length())));
        if (prompt != null && prompt.contains(DRAFT_MARK)) {
            int idx = prompt.indexOf(DRAFT_MARK) + DRAFT_MARK.length();
            int end = prompt.indexOf('\n', idx);
            String draft = (end > idx ? prompt.substring(idx, end) : prompt.substring(idx)).trim();
            if (!draft.isEmpty()) {
                return LlmReply.mock("[MOCK] " + draft);
            }
        }
        return LlmReply.mock("[MOCK] 这是开发期占位回复,尚未接入真实 LLM 网关(设置中心配好 LLM_BASE_URL/LLM_API_KEY 后自动切换)。");
    }
}
