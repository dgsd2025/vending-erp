package top.aole.vend.modules.ai.domain;

/**
 * LLM 服务接口(AI 网关)。
 * 实现:MockLlmService(无 key 断路,零外呼)/ OpenAiCompatLlmService(真网关)/
 * RoutingLlmService(@Primary,按 llm.* 是否配置自动切换,业务方只注入本接口)。
 */
public interface ILlmService {

    /**
     * 单轮对话(默认模型)。
     *
     * @param prompt 用户输入
     * @return 模型回复文本
     */
    String chat(String prompt);

    /**
     * 单轮对话(指定模型,多模型路由用)。
     *
     * @param model  模型名(mock 实现忽略)
     * @param prompt 用户输入
     * @return 回复 + token 用量
     */
    LlmReply chat(String model, String prompt);
}
