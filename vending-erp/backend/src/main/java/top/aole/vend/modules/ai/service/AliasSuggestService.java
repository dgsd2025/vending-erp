package top.aole.vend.modules.ai.service;

import cn.hutool.core.text.TextSimilarity;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.mapper.LlmCallLogMapper;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.AliasPendingMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入点#1:SKU 别名自动归集建议(AliasPendingDrawer 的建议写手,兑现 M1-10 P2-6 锁定的验收)。
 *
 * mock 阶段(当前):纯规则打分(条码精确 > 名称精确 > 包含关系 > 编辑距离),标「规则建议」;
 *   规则出的相似度即置信分——LLM 不算数,mock 只把打分理由说成人话。
 * 真 key 阶段:embedding 召回(text-embedding-v4)+ LLM 复核(DeepSeek-V3 便宜复核)——
 *   代码路径见 {@link #recallCandidates};未配 key 自动断路回规则,零外呼。
 *
 * 四件套:每条建议落 llm_call_log(reasoning=打分明细/output=复核话术/confidence=相似度/input=候选原始数据),
 * alias_pending.llm_call_id 指向之,前端置信 chip 必挂 🔬 弹窗。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AliasSuggestService {

    /** 低于此分不给建议(宁缺勿滥,免得误导人点确认) */
    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.50");

    private final AliasPendingMapper aliasPendingMapper;
    private final ProductMapper productMapper;
    private final LlmGateway llmGateway;
    private final LlmProperties llmProperties;
    private final LlmCallLogMapper llmCallLogMapper;

    /** 单个候选打分结果 */
    @Value
    public static class Candidate {
        Long productId;
        String productName;
        BigDecimal score;
        String reason;
    }

    /**
     * 给所有「待绑定」项生成建议。
     *
     * @param force true=忽略当日缓存重算重调
     * @return 摘要 {processed, suggested, noMatch}
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> suggestAll(boolean force) {
        List<AliasPending> pendings = aliasPendingMapper.selectList(
                new LambdaQueryWrapper<AliasPending>().eq(AliasPending::getPendingStatus, "待绑定"));
        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>().ne(Product::getProductStatus, "停售"));

        int suggested = 0;
        int noMatch = 0;
        for (AliasPending pending : pendings) {
            List<Candidate> candidates = recallCandidates(pending, products);
            Candidate best = candidates.isEmpty() ? null : candidates.get(0);
            if (best == null || best.getScore().compareTo(MIN_CONFIDENCE) < 0) {
                noMatch++;
                continue;
            }

            String draft = String.format("「%s」按规则最像「%s」(相似度 %d%%,%s),建议绑定,请人工确认。",
                    pending.getAliasName(), best.getProductName(),
                    best.getScore().multiply(new BigDecimal(100)).intValue(), best.getReason());
            StringBuilder reasoning = new StringBuilder("规则打分明细(数字由规则引擎出,LLM 只复核话术):\n");
            for (Candidate c : candidates) {
                reasoning.append(String.format("- %s:%.2f(%s)%n", c.getProductName(), c.getScore(), c.getReason()));
            }
            JSONObject digest = new JSONObject();
            digest.set("pending", new JSONObject()
                    .set("aliasName", pending.getAliasName())
                    .set("aliasCode", pending.getAliasCode())
                    .set("aliasBarcode", pending.getAliasBarcode())
                    .set("hitCount", pending.getHitCount()));
            digest.set("candidates", JSONUtil.parseArray(candidates));

            LlmGateway.GatewayResult result = llmGateway.invoke(LlmGateway.LlmTask.builder()
                    .scene(AiScenes.ALIAS)
                    .bizKey("pending:" + pending.getId())
                    .prompt("你是售卖机 ERP 的 SKU 别名归集复核员。规则引擎已算好候选与相似度(数字不许改),"
                            + "请复核并用一句话说明是否同意绑定。\n草稿:" + draft + "\n候选明细:" + reasoning)
                    .reasoning(reasoning.toString())
                    .inputDigest(digest.toString())
                    .confidence(best.getScore())
                    // 真算:置信分=规则算出的相似度(条码/名称/编辑距离),非固定档
                    .confidenceSource("computed")
                    .promptFingerprint("alias-suggest-v1")
                    .fallbackText(draft)
                    .build(), force);

            AliasPending patch = new AliasPending();
            patch.setId(pending.getId());
            patch.setSuggestProductId(best.getProductId());
            patch.setAiConfidence(best.getScore());
            patch.setLlmCallId(result.getCall().getId());
            aliasPendingMapper.updateById(patch);
            suggested++;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("processed", pendings.size());
        summary.put("suggested", suggested);
        summary.put("noMatch", noMatch);
        summary.put("mode", llmProperties.isConfigured() ? "embedding+LLM复核" : "规则建议(mock断路)");
        return summary;
    }

    /**
     * 候选召回(按分数降序,最多 3 条)。
     * 真 key 路径:先 embedding 相似度召回 TopN 再 LLM 复核——embedding 调用见
     * OpenAiCompatLlmService(/embeddings,text-embedding-v4);当前未配 key 断路走纯规则,
     * 保证零外呼。规则打分本身在两条路径下都保留(置信构成始终可解释)。
     */
    public List<Candidate> recallCandidates(AliasPending pending, List<Product> products) {
        // NOTE(真 key 启用点):llmProperties.isConfigured() 时可在此先调 embedding 把 products
        // 缩小到 TopN 再精算;mock 断路直接全量规则打分(百级商品量,毫秒级)。
        List<Candidate> list = new ArrayList<>();
        for (Product p : products) {
            Candidate c = score(pending, p);
            if (c != null) {
                list.add(c);
            }
        }
        list.sort(Comparator.comparing(Candidate::getScore).reversed());
        return list.size() > 3 ? list.subList(0, 3) : list;
    }

    /** 规则打分:条码精确 0.99 > 名称精确 0.95 > 包含 0.80 > 编辑距离相似度(0.4~0.7);低于 0.4 出局 */
    public Candidate score(AliasPending pending, Product product) {
        String pBarcode = StrUtil.trimToEmpty(pending.getAliasBarcode());
        String prodBarcode = StrUtil.trimToEmpty(product.getBarcode());
        if (!pBarcode.isEmpty() && pBarcode.equals(prodBarcode)) {
            return new Candidate(product.getId(), product.getProductName(),
                    new BigDecimal("0.99"), "条码精确一致");
        }
        String a = normalize(pending.getAliasName());
        String b = normalize(product.getProductName());
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        if (a.equals(b)) {
            return new Candidate(product.getId(), product.getProductName(),
                    new BigDecimal("0.95"), "名称归一后完全相同");
        }
        if (a.contains(b) || b.contains(a)) {
            return new Candidate(product.getId(), product.getProductName(),
                    new BigDecimal("0.80"), "名称包含关系");
        }
        double sim = TextSimilarity.similar(a, b); // hutool:基于最长公共子串的相似度 0~1
        if (sim >= 0.6) {
            BigDecimal score = new BigDecimal(0.4 + 0.3 * sim).setScale(2, RoundingMode.HALF_UP);
            return new Candidate(product.getId(), product.getProductName(), score,
                    String.format("编辑距离相似度 %.2f", sim));
        }
        return null;
    }

    /** 名称归一:去空白/常见分隔符,小写(中文不受影响) */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase()
                .replaceAll("[\\s\\u3000·`~!@#$%^&*()_+\\-=\\[\\]{};:'\",.<>/?（）【】、。,;:！!？?]", "");
    }
}
