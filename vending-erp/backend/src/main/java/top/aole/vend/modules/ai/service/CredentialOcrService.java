package top.aole.vend.modules.ai.service;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.mapper.LlmCallLogMapper;
import top.aole.vend.modules.money.domain.entity.Attachment;
import top.aole.vend.modules.money.mapper.AttachmentMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接入点#2:凭证智能识别(转账截图/平台账单 → 金额/日期/对方,预填表单可人工改)。
 *
 * mock 阶段(当前):不读图不外呼——从文件名里尽力抠数字当金额(抠不到给占位 100),
 *   日期=今天,对方=[MOCK] 占位;所有值标 source=mock,前端预填后必须人工核改。
 * 真 key 阶段:Qwen-VL(中文票据识别最优,§12.2)——图片 base64 走 OpenAI 兼容
 *   vision 协议,提示词已写好(见 VISION_PROMPT);未配 key 自动断路,零外呼。
 *
 * 结果写 attachment.ocr_json + llm_call_id;四件套落 llm_call_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialOcrService {

    /** 真 key 时给 Qwen-VL 的提示词(JSON 抽取,LLM 只抽取不算账) */
    static final String VISION_PROMPT =
            "你是财务凭证识别员。请从这张凭证图片中提取:金额(数字)、日期(yyyy-MM-dd)、对方名称。"
                    + "只输出 JSON:{\"amount\":?,\"bizDate\":\"?\",\"counterparty\":\"?\"};识别不出的字段填 null,不许编造。";

    private static final Pattern AMOUNT_IN_NAME = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)");

    private final AttachmentMapper attachmentMapper;
    private final LlmGateway llmGateway;
    private final LlmProperties llmProperties;
    private final AiModelRouteService routeService;
    private final LlmCallLogMapper llmCallLogMapper;
    private final top.aole.vend.modules.ai.infrastructure.OpenAiCompatLlmService openAiCompatLlmService;

    /**
     * 识别一张凭证,返回预填字段(前端填进付款/结算表单,人工可改)。
     */
    @Transactional(rollbackFor = Exception.class)
    public JSONObject recognize(Long attachmentId, boolean force) {
        Attachment att = attachmentMapper.selectById(attachmentId);
        if (att == null) {
            throw new BizException("凭证附件不存在:id=" + attachmentId);
        }
        // 24h idempotent 前置检查:当日已识别过且没点"重新识别" → 直接复用,不重跑 vision
        if (!force && att.getOcrJson() != null) {
            top.aole.vend.modules.ai.domain.entity.LlmCallLog prev =
                    att.getLlmCallId() == null ? null : llmCallLogMapper.selectById(att.getLlmCallId());
            if (prev != null && prev.getCreateTime() != null
                    && prev.getCreateTime().toLocalDate().equals(LocalDate.now())) {
                JSONObject cached = new JSONObject(att.getOcrJson());
                cached.set("cacheHit", true);
                return cached;
            }
        }

        JSONObject extracted;
        String reasoning;
        if (llmProperties.isConfigured()) {
            // 真 key 路径(Qwen-VL vision):读文件→base64→chat vision→解析 JSON;失败降级 mock 占位
            extracted = visionExtract(att);
            reasoning = "Qwen-VL 视觉识别(真网关):图片 base64 → vision 协议 → JSON 抽取;LLM 只抽取不算账。";
        } else {
            extracted = mockExtract(att);
            reasoning = "mock 断路:未配 LLM key,不读图不外呼。金额从文件名抠数字(抠不到给占位 100),"
                    + "日期取今天,对方为占位文案——预填后必须人工核改。";
        }

        String prefix = llmProperties.isConfigured() ? "" : "[MOCK] ";
        String draft = String.format("%s识别到金额 %s 元,日期 %s,对方「%s」,已预填表单,请人工核对修改。",
                prefix, extracted.getStr("amount"), extracted.getStr("bizDate"), extracted.getStr("counterparty"));

        // 真调用(vision)已在 visionExtract 里发生 / mock 无调用 → 走 record 落四件套,不再二次对话
        LlmGateway.GatewayResult result = llmGateway.record(LlmGateway.LlmTask.builder()
                .scene(AiScenes.OCR)
                .bizKey("att:" + attachmentId)
                .prompt(VISION_PROMPT)
                .reasoning(reasoning)
                .inputDigest(new JSONObject()
                        .set("attachmentId", attachmentId)
                        .set("fileName", att.getFileName())
                        .set("attType", att.getAttType())
                        .set("refType", att.getRefType())
                        .set("refId", att.getRefId()).toString())
                .confidence(llmProperties.isConfigured() ? new BigDecimal("0.80") : new BigDecimal("0.30"))
                .promptFingerprint("credential-ocr-v1")
                .fallbackText(draft)
                .build(), top.aole.vend.modules.ai.domain.LlmReply.mock(draft), force);

        JSONObject ocr = new JSONObject();
        ocr.set("amount", extracted.get("amount"));
        ocr.set("bizDate", extracted.getStr("bizDate"));
        ocr.set("counterparty", extracted.getStr("counterparty"));
        ocr.set("source", llmProperties.isConfigured() ? "qwen-vl" : "mock");
        ocr.set("llmCallId", result.getCall().getId());
        ocr.set("cacheHit", result.isCacheHit());

        Attachment patch = new Attachment();
        patch.setId(att.getId());
        patch.setOcrJson(ocr.toString());
        patch.setLlmCallId(result.getCall().getId());
        attachmentMapper.updateById(patch);
        return ocr;
    }

    /**
     * 真 key 抽取(Qwen-VL):读归档文件 → base64 → vision 调用 → 解析 JSON;
     * 任一步失败降级 mockExtract(业务不断,callStatus 由网关记降级)。
     */
    private JSONObject visionExtract(Attachment att) {
        try {
            java.io.File file = new java.io.File(att.getFilePath());
            byte[] bytes = cn.hutool.core.io.FileUtil.readBytes(file);
            String ext = cn.hutool.core.io.FileUtil.extName(file.getName()).toLowerCase();
            String mime = "png".equals(ext) ? "image/png" : "image/jpeg";
            String model = routeService.resolve(AiScenes.OCR);
            String reply = openAiCompatLlmService.chatVision(model, VISION_PROMPT, bytes, mime).getText();
            String json = reply.replaceAll("(?s).*?(\\{.*}).*", "$1"); // 剥掉围栏/闲话只留 JSON
            JSONObject parsed = new JSONObject(json);
            return new JSONObject()
                    .set("amount", parsed.get("amount"))
                    .set("bizDate", parsed.getStr("bizDate"))
                    .set("counterparty", parsed.getStr("counterparty"))
                    .set("fileName", att.getFileName());
        } catch (Exception ex) {
            log.warn("[OCR] vision 识别失败,降级 mock 占位:{}", ex.getMessage());
            return mockExtract(att);
        }
    }

    /** mock 抽取:从文件名抠第一个数字当金额(抠不到给 100 占位) */
    private JSONObject mockExtract(Attachment att) {
        BigDecimal amount = new BigDecimal("100.00");
        String name = att.getFileName() == null ? "" : att.getFileName();
        Matcher m = AMOUNT_IN_NAME.matcher(name);
        if (m.find()) {
            try {
                amount = new BigDecimal(m.group(1));
            } catch (NumberFormatException ignore) {
                // 文件名里的数字不是钱,保持占位
            }
        }
        return new JSONObject()
                .set("amount", amount)
                .set("bizDate", LocalDate.now().toString())
                .set("counterparty", "[MOCK] 微信转账-对方待核")
                .set("fileName", name);
    }
}
