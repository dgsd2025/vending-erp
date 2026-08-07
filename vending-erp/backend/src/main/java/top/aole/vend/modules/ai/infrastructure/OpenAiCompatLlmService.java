package top.aole.vend.modules.ai.infrastructure;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.ILlmService;
import top.aole.vend.modules.ai.domain.LlmReply;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 真网关实现:OpenAI 兼容协议 POST {baseUrl}/chat/completions。
 * 断路铁律:llm.* 未配置时绝不发网络请求(先 isConfigured 硬校验,测试断言 requestCount==0)。
 * 业务方永远不要直接注入本类——走 RoutingLlmService(@Primary)自动路由。
 */
@Slf4j
@Service("openAiCompatLlmService")
@RequiredArgsConstructor
public class OpenAiCompatLlmService implements ILlmService {

    private final LlmProperties llmProperties;

    /** 累计真实外呼次数(mock 断路测试的证据:无 key 时恒为 0) */
    private final AtomicLong requestCount = new AtomicLong(0);

    public long getRequestCount() {
        return requestCount.get();
    }

    @Override
    public String chat(String prompt) {
        return chat(llmProperties.getModel(), prompt).getText();
    }

    @Override
    public LlmReply chat(String model, String prompt) {
        if (!llmProperties.isConfigured()) {
            // 断路:没配 key 走到这里属于编程错误(应由 RoutingLlmService 分流去 mock)
            throw new BizException("LLM 网关未配置(LLM_BASE_URL/LLM_API_KEY 为空),拒绝外呼");
        }
        requestCount.incrementAndGet();
        String url = llmProperties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        JSONObject body = new JSONObject();
        body.set("model", model == null || model.isEmpty() ? llmProperties.getModel() : model);
        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "user").set("content", prompt));
        body.set("messages", messages);

        long start = System.currentTimeMillis();
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + llmProperties.getApiKey())
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(60_000)
                .execute()) {
            if (!resp.isOk()) {
                throw new BizException("LLM 网关返回 " + resp.getStatus() + ":" + abbreviate(resp.body()));
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            String text = json.getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getStr("content", "");
            JSONObject usage = json.getJSONObject("usage");
            LlmReply reply = new LlmReply(text,
                    usage == null ? null : usage.getInt("prompt_tokens"),
                    usage == null ? null : usage.getInt("completion_tokens"),
                    "openai-compat");
            log.info("[LLM] {} 调用成功,{}ms,tokens {}/{}", body.getStr("model"),
                    System.currentTimeMillis() - start, reply.getTokensIn(), reply.getTokensOut());
            return reply;
        }
    }

    /**
     * 视觉对话(接入点#2 凭证识别,Qwen-VL):OpenAI 兼容 vision 协议,图片走 base64 data URL。
     * 同样受断路保护:未配置绝不外呼。
     */
    public LlmReply chatVision(String model, String prompt, byte[] image, String mime) {
        if (!llmProperties.isConfigured()) {
            throw new BizException("LLM 网关未配置,拒绝外呼(vision)");
        }
        requestCount.incrementAndGet();
        String url = llmProperties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        String dataUrl = "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(image);
        JSONArray content = new JSONArray();
        content.add(new JSONObject().set("type", "text").set("text", prompt));
        content.add(new JSONObject().set("type", "image_url")
                .set("image_url", new JSONObject().set("url", dataUrl)));
        JSONArray visionMessages = new JSONArray();
        visionMessages.add(new JSONObject().set("role", "user").set("content", content));
        JSONObject body = new JSONObject()
                .set("model", model)
                .set("messages", visionMessages);
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + llmProperties.getApiKey())
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(120_000)
                .execute()) {
            if (!resp.isOk()) {
                throw new BizException("LLM vision 网关返回 " + resp.getStatus() + ":" + abbreviate(resp.body()));
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            String text = json.getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getStr("content", "");
            JSONObject usage = json.getJSONObject("usage");
            return new LlmReply(text,
                    usage == null ? null : usage.getInt("prompt_tokens"),
                    usage == null ? null : usage.getInt("completion_tokens"),
                    "openai-compat");
        }
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
