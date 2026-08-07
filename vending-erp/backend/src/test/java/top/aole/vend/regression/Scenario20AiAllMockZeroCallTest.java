package top.aole.vend.regression;

import cn.hutool.core.util.IdUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.modules.ai.domain.IPdcaDraftService;
import top.aole.vend.modules.ai.infrastructure.OpenAiCompatLlmService;
import top.aole.vend.modules.ai.mapper.LlmCallLogMapper;
import top.aole.vend.modules.ai.service.AliasSuggestService;
import top.aole.vend.modules.ai.service.AnomalyService;
import top.aole.vend.modules.ai.service.CredentialOcrService;
import top.aole.vend.modules.ai.service.InsightService;
import top.aole.vend.modules.ai.service.NlQueryService;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.AliasPendingMapper;
import top.aole.vend.modules.money.domain.entity.Attachment;
import top.aole.vend.modules.money.mapper.AttachmentMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-7 全链路场景20:AI 全 mock 零外呼(vend_test_regression 独立库,每例事务回滚)。
 *
 * 一次跑遍 AI 六大挂点(别名/凭证/解释/异常/NL 查数/PDCA 起草),集中断言三件事:
 *   ① OpenAiCompatLlmService 请求计数全程零增长(llm.* 未配 → RoutingLlmService 走 MockLlmService 断路);
 *   ② 每个挂点都往 llm_call_log 落库(过程可追溯);
 *   ③ idempotent 当日命中缓存不重复烧钱(PDCA 起草 + 别名建议二次调各验一次)。
 *
 * 说明:test-regression profile 未配 llm.base-url/api-key(base application.yml 默认空)→ 强制 mock。
 */
class Scenario20AiAllMockZeroCallTest extends RegressionSupport {

    @Autowired private AliasSuggestService aliasSuggestService;
    @Autowired private CredentialOcrService credentialOcrService;
    @Autowired private InsightService insightService;
    @Autowired private AnomalyService anomalyService;
    @Autowired private NlQueryService nlQueryService;
    @Autowired private IPdcaDraftService pdcaDraftService;

    @Autowired private OpenAiCompatLlmService openAiCompatLlmService;
    @Autowired private LlmCallLogMapper llmCallLogMapper;
    @Autowired private LlmProperties llmProperties;
    @Autowired private AliasPendingMapper aliasPendingMapper;
    @Autowired private AttachmentMapper attachmentMapper;

    private long externalCalls() {
        return openAiCompatLlmService.getRequestCount();
    }

    private long totalLogs() {
        return llmCallLogMapper.selectCount(null);
    }

    private AliasPending pending(String name, String barcode) {
        AliasPending ap = new AliasPending();
        ap.setAliasCode("S20-" + IdUtil.fastSimpleUUID().substring(0, 6));
        ap.setAliasBarcode(barcode == null ? "" : barcode);
        ap.setAliasName(name);
        ap.setHitCount(3);
        ap.setPendingStatus("待绑定");
        aliasPendingMapper.insert(ap);
        return ap;
    }

    @Test
    @DisplayName("六挂点全跑一遍:OpenAiCompat 零请求 + 每挂点 llm_call_log 落库 + PDCA/别名 idempotent 命中")
    void allHooksMockZeroOutbound() {
        // ---- 前置:强制 mock 断路(未配真网关)----
        assertFalse(llmProperties.isConfigured(), "test-regression 下 llm.* 未配置,必须走 mock");
        long call0 = externalCalls();
        long log0 = totalLogs();

        // ---- #1 别名建议 ----
        Product coke = product("可口可乐330ml", "6901234500011", null);
        product("农夫山泉550ml", "6901234500022", null);
        AliasPending byBarcode = pending("可乐(后台名不同)", "6901234500011");
        Map<String, Object> aliasSummary = aliasSuggestService.suggestAll(false);
        assertThat(aliasSummary.get("mode").toString().contains("规则建议"), "别名走规则/mock 模式");
        AliasPending b1 = aliasPendingMapper.selectById(byBarcode.getId());
        assertEquals(coke.getId(), b1.getSuggestProductId(), "条码精确命中");
        assertNotNull(b1.getLlmCallId(), "别名挂点落 llm_call_id");

        // ---- #2 凭证识别 ----
        Attachment att = new Attachment();
        att.setRefType("settlement");
        att.setRefId(1L);
        att.setAttType("平台账单");
        att.setFilePath("/tmp/s20-nonexist.png");
        att.setFileName("转账截图-金额388.50.png");
        attachmentMapper.insert(att);
        @SuppressWarnings("unchecked")
        Map<String, Object> ocr = (Map<String, Object>) (Object) credentialOcrService.recognize(att.getId(), false);
        assertEquals("mock", ocr.get("source"), "凭证识别走 mock");
        assertNotNull(ocr.get("llmCallId"), "凭证挂点落 llm_call_id");

        // ---- #3 解释层三挂点 ----
        for (String sub : new String[]{
                InsightService.SCENE_STOCKTAKE, InsightService.SCENE_SETTLEMENT, InsightService.SCENE_ASSET}) {
            Map<String, Object> ins = insightService.generate(sub, false);
            assertNotNull(ins.get("llmCallId"), "解释挂点[" + sub + "]落 llm_call_id");
        }

        // ---- #4 异常侦探(销量骤变 + 归因叙事)----
        Long anomalyPid = product("异常侦测东鹏", "6900000000777", null).getId();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 10; i++) {
            insertSale(1L, anomalyPid, "1", "正常", now.minusDays(10));
        }
        for (int i = 0; i < 3; i++) {
            insertSale(1L, anomalyPid, "1", "正常", now.minusDays(2));
        }
        List<AnomalyService.Anomaly> anomalies = anomalyService.detect();
        AnomalyService.Anomaly swing = anomalies.stream()
                .filter(a -> "sales-swing".equals(a.getType())).findFirst().orElse(null);
        assertNotNull(swing, "应侦测到销量骤变");
        Map<String, Object> explain = anomalyService.explain(swing, false);
        assertNotNull(explain.get("llmCallId"), "异常归因落 llm_call_id");

        // ---- #6 NL 查数(白名单视图 + 模板)----
        insertSale(null, product("NL测试品", "6900000009999", null).getId(), "2", "正常", now);
        NlQueryService.QueryResult nl = nlQueryService.ask("各机器毛利多少", false);
        assertTrue(nl.getMode().contains("mock"), "NL 查数走 mock");
        assertNotNull(nl.getLlmCallId(), "NL 挂点落 llm_call_id");

        // ---- #7 PDCA 起草 ----
        IPdcaDraftService.PdcaDraftResult draft = pdcaDraftService.draft(
                IPdcaDraftService.PdcaDraftReq.builder()
                        .bizKey("check:1:动销率:2026-08")
                        .metricName("动销率")
                        .actualValue(new BigDecimal("0.40"))
                        .targetValue(new BigDecimal("0.60"))
                        .scope("场景20全店")
                        .build());
        assertNotNull(draft.getLlmCallId(), "PDCA 起草落 llm_call_id");
        assertTrue(draft.getModel().startsWith("mock("), "PDCA 起草走 mock 模型");

        // ---- ① 全程零外呼:六挂点跑完 OpenAiCompat 请求计数纹丝不动 ----
        assertEquals(call0, externalCalls(), "六挂点全 mock,OpenAiCompatLlmService 零请求");

        // ---- ② 全部落库:日志显著增长(六大挂点各≥1 条)----
        long logsAfter = totalLogs();
        assertTrue(logsAfter - log0 >= 6, "六挂点至少落 6 条 llm_call_log,实增:" + (logsAfter - log0));

        // ---- ③ idempotent:PDCA 同 bizKey 当日二次命中缓存(不新增 log)----
        IPdcaDraftService.PdcaDraftResult draft2 = pdcaDraftService.draft(
                IPdcaDraftService.PdcaDraftReq.builder()
                        .bizKey("check:1:动销率:2026-08")
                        .metricName("动销率")
                        .actualValue(new BigDecimal("0.40"))
                        .targetValue(new BigDecimal("0.60"))
                        .scope("场景20全店")
                        .build());
        assertTrue(draft2.isCacheHit(), "PDCA 起草同 bizKey 当日命中缓存");
        assertEquals(draft.getLlmCallId(), draft2.getLlmCallId(), "命中缓存复用同一 llm_call_id");

        // 别名二次建议(非 force):当日命中缓存,不新增日志
        long logsBeforeRerun = totalLogs();
        aliasSuggestService.suggestAll(false);
        assertEquals(logsBeforeRerun, totalLogs(), "别名二次建议命中缓存,零新增日志");

        // 收口再断一次零外呼
        assertEquals(call0, externalCalls(), "idempotent 复跑仍零外呼");
    }

    private static void assertThat(boolean cond, String msg) {
        assertTrue(cond, msg);
    }
}
