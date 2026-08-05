package top.aole.vend.modules.ai.domain;

/**
 * LLM 服务接口(AI 网关占位)。
 * 开发期唯一实现是 MockLlmService;接真网关时新增实现类并按 llm.* 配置切换。
 */
public interface ILlmService {

    /**
     * 单轮对话。
     *
     * @param prompt 用户输入
     * @return 模型回复文本
     */
    String chat(String prompt);
}
