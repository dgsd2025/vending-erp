package top.aole.vend.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 网关配置(OpenAI 兼容),值从 ENV 读,默认空。
 * 开发期全 mock(见 MockLlmService),不发真请求;上线前再接真网关。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** OpenAI 兼容网关地址,ENV: LLM_BASE_URL */
    private String baseUrl = "";

    /** API Key,ENV: LLM_API_KEY(永不硬编码/入库) */
    private String apiKey = "";

    /** 默认模型名,ENV: LLM_MODEL */
    private String model = "";

    /** 是否已配置真实网关 */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isEmpty()
                && apiKey != null && !apiKey.isEmpty();
    }
}
