package top.aole.vend.modules.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 单次调用返回:文本 + token 用量(mock 时用量为 0)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmReply {

    private String text;

    private Integer tokensIn;

    private Integer tokensOut;

    /** 实际执行方:mock / openai-compat */
    private String executor;

    public static LlmReply mock(String text) {
        return new LlmReply(text, 0, 0, "mock");
    }
}
