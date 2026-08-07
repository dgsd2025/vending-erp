package top.aole.vend.modules.ai;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.domain.IPdcaDraftService;
import top.aole.vend.modules.ai.domain.entity.LlmCallLog;
import top.aole.vend.modules.ai.infrastructure.OpenAiCompatLlmService;
import top.aole.vend.modules.ai.mapper.LlmCallLogMapper;
import top.aole.vend.modules.ai.service.AiModelRouteService;
import top.aole.vend.modules.ai.service.AliasSuggestService;
import top.aole.vend.modules.ai.service.AnomalyService;
import top.aole.vend.modules.ai.service.CredentialOcrService;
import top.aole.vend.modules.ai.service.InsightService;
import top.aole.vend.modules.ai.service.NlQueryService;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.AliasPendingMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.money.domain.entity.Attachment;
import top.aole.vend.modules.money.mapper.AttachmentMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4-5 AI 接入点全家桶集成测试(vend_test_ai 库)。
 *
 * 覆盖验收清单:
 * - idempotent 当日命中不重调 / force 重调
 * - mock 断路:无 key 全程零外呼(OpenAiCompatLlmService.requestCount==0)
 * - #1 别名规则打分正确性
 * - #6 白名单外表名拒绝 + 非 SELECT 拒绝
 * - llm_call_log 每次落库
 * - #2/#3/#4/#7 各挂点走一遍(mock 文案带 [MOCK])
 * - 模型路由覆盖
 */
@ActiveProfiles("test-ai")
class AiFamilyTest extends BaseIntegrationTest {

    @Autowired private AliasSuggestService aliasSuggestService;
    @Autowired private CredentialOcrService credentialOcrService;
    @Autowired private InsightService insightService;
    @Autowired private AnomalyService anomalyService;
    @Autowired private NlQueryService nlQueryService;
    @Autowired private AiModelRouteService routeService;
    @Autowired private IPdcaDraftService pdcaDraftService;

    @Autowired private ProductMapper productMapper;
    @Autowired private AliasPendingMapper aliasPendingMapper;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private LlmCallLogMapper llmCallLogMapper;
    @Autowired private LlmProperties llmProperties;
    @Autowired private OpenAiCompatLlmService openAiCompatLlmService;
    @Autowired private JdbcTemplate jdbc;

    // ============================== 造数 ==============================

    private Product product(String sku, String name, String barcode) {
        Product p = new Product();
        p.setSkuCode(sku);
        p.setProductName(name);
        p.setBarcode(barcode);
        p.setUnit("瓶");
        p.setBoxSpec(new BigDecimal("24"));
        p.setProductStatus("在售");
        productMapper.insert(p);
        return p;
    }

    private AliasPending pending(String name, String barcode) {
        AliasPending ap = new AliasPending();
        ap.setAliasCode("BK-" + IdUtil.fastSimpleUUID().substring(0, 6));
        ap.setAliasBarcode(barcode == null ? "" : barcode);
        ap.setAliasName(name);
        ap.setHitCount(3);
        ap.setPendingStatus("待绑定");
        aliasPendingMapper.insert(ap);
        return ap;
    }

    private long externalCalls() {
        return openAiCompatLlmService.getRequestCount();
    }

    // ============================== 0. 前置:确认 mock 断路环境 ==============================

    @Test
    @DisplayName("环境断言:测试 profile 下 llm 未配置(强制走 mock)")
    void envIsMock() {
        assertThat(llmProperties.isConfigured()).isFalse();
    }

    // ============================== 1. #1 别名规则打分 ==============================

    @Test
    @DisplayName("#1 规则打分:条码精确=0.99 > 名称精确=0.95 > 包含=0.80;写回 suggest/confidence/llm_call_id")
    void aliasScoring() {
        long before = externalCalls();
        Product coke = product("SP-COKE", "可口可乐330ml", "6901234500011");
        product("SP-WATER", "农夫山泉550ml", "6901234500022");

        // 条码精确命中
        AliasPending byBarcode = pending("可乐(后台名不同)", "6901234500011");
        // 名称包含命中
        AliasPending byName = pending("可口可乐", null);

        Map<String, Object> summary = aliasSuggestService.suggestAll(false);
        assertThat(summary.get("mode").toString()).contains("规则建议");

        AliasPending b1 = aliasPendingMapper.selectById(byBarcode.getId());
        assertThat(b1.getSuggestProductId()).isEqualTo(coke.getId());
        assertThat(b1.getAiConfidence()).isEqualByComparingTo("0.99");
        assertThat(b1.getLlmCallId()).isNotNull();

        AliasPending b2 = aliasPendingMapper.selectById(byName.getId());
        assertThat(b2.getSuggestProductId()).isEqualTo(coke.getId());
        assertThat(b2.getAiConfidence()).isEqualByComparingTo("0.80");

        // 四件套落库
        LlmCallLog log = llmCallLogMapper.selectById(b1.getLlmCallId());
        assertThat(log.getScene()).isEqualTo(AiScenes.ALIAS.getLabel());
        assertThat(log.getModel()).startsWith("mock(");
        assertThat(log.getReasoning()).contains("规则打分明细");

        // 断路:零外呼
        assertThat(externalCalls()).isEqualTo(before);
    }

    @Test
    @DisplayName("#1 相似度太低不给建议(noMatch),不硬塞")
    void aliasNoMatch() {
        product("SP-A", "青岛啤酒", "6900000000001");
        pending("康师傅红烧牛肉面", "6911111111111");
        Map<String, Object> summary = aliasSuggestService.suggestAll(false);
        assertThat(((Number) summary.get("noMatch")).intValue()).isGreaterThanOrEqualTo(1);
    }

    // ============================== 2. idempotent ==============================

    @Test
    @DisplayName("idempotent:同 key 当日第二次命中缓存,不新增 llm_call_log;force=true 重调新增")
    void idempotency() {
        product("SP-COKE", "可口可乐", "6901234500011");
        AliasPending ap = pending("可口可乐", "6901234500011");

        aliasSuggestService.suggestAll(false);
        AliasPending first = aliasPendingMapper.selectById(ap.getId());
        Long call1 = first.getLlmCallId();
        long logs1 = countLogs(AiScenes.ALIAS.getLabel());

        // 第二次(非 force):命中当日缓存,不新增 log
        aliasSuggestService.suggestAll(false);
        long logs2 = countLogs(AiScenes.ALIAS.getLabel());
        assertThat(logs2).isEqualTo(logs1);

        // force:重调,新增 log
        aliasSuggestService.suggestAll(true);
        long logs3 = countLogs(AiScenes.ALIAS.getLabel());
        assertThat(logs3).isGreaterThan(logs2);
        assertThat(call1).isNotNull();
    }

    private long countLogs(String scene) {
        return llmCallLogMapper.selectCount(new LambdaQueryWrapper<LlmCallLog>()
                .eq(LlmCallLog::getScene, scene));
    }

    // ============================== 3. #6 NL 查数安全闸 ==============================

    @Test
    @DisplayName("#6 安全闸:白名单外表名拒绝 / 非 SELECT 拒绝 / 多语句拒绝 / 强制 LIMIT")
    void nlSafetyGate() {
        // 白名单外表
        assertThatThrownBy(() -> nlQueryService.enforceSafety("SELECT * FROM yc_vend_sale_record"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("白名单");
        // 非 SELECT
        assertThatThrownBy(() -> nlQueryService.enforceSafety("DELETE FROM v_ai_loss"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SELECT");
        assertThatThrownBy(() -> nlQueryService.enforceSafety("UPDATE v_ai_loss SET 原因='x'"))
                .isInstanceOf(BizException.class);
        // 多语句注入
        assertThatThrownBy(() -> nlQueryService.enforceSafety(
                "SELECT * FROM v_ai_loss; DROP TABLE yc_vend_product"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("多条语句");
        // 注释
        assertThatThrownBy(() -> nlQueryService.enforceSafety("SELECT * FROM v_ai_loss -- x"))
                .isInstanceOf(BizException.class);
        // 合法:强制补 LIMIT
        String safe = nlQueryService.enforceSafety("SELECT 月份 FROM v_ai_month_gross");
        assertThat(safe.toLowerCase()).contains("limit");
    }

    @Test
    @DisplayName("#6 真查:模板匹配 + 执行白名单视图,落 log,零外呼")
    void nlQueryRun() {
        long before = externalCalls();
        // 造一条毛利数据(视图从 sale_record 聚合)
        insertSale(null, seedProductId(), "2", "正常", java.time.LocalDateTime.now());
        NlQueryService.QueryResult r = nlQueryService.ask("各机器毛利多少", false);
        assertThat(r.getSql().toLowerCase()).contains("v_ai_month_gross");
        assertThat(r.getMode()).contains("mock");
        assertThat(r.getLlmCallId()).isNotNull();
        assertThat(externalCalls()).isEqualTo(before);
    }

    private Long seedProductId() {
        Product p = product("SP-SEED", "测试品", "6900000009999");
        return p.getId();
    }

    // ============================== 4. #2 凭证识别 ==============================

    @Test
    @DisplayName("#2 凭证识别:mock 从文件名抠金额,预填 source=mock,写 ocr_json + llm_call_id")
    void ocrMock() {
        long before = externalCalls();
        Attachment att = new Attachment();
        att.setRefType("settlement");
        att.setRefId(1L);
        att.setAttType("平台账单");
        att.setFilePath("/tmp/nonexist.png");
        att.setFileName("转账截图-金额388.50.png");
        attachmentMapper.insert(att);

        Map<String, Object> ocr = (Map<String, Object>) (Object) credentialOcrService.recognize(att.getId(), false);
        assertThat(ocr.get("source")).isEqualTo("mock");
        assertThat(new BigDecimal(ocr.get("amount").toString())).isEqualByComparingTo("388.50");
        assertThat(ocr.get("llmCallId")).isNotNull();

        Attachment saved = attachmentMapper.selectById(att.getId());
        assertThat(saved.getOcrJson()).contains("388.5");
        assertThat(saved.getLlmCallId()).isNotNull();
        assertThat(externalCalls()).isEqualTo(before);
    }

    // ============================== 5. #3 解释层 ==============================

    @Test
    @DisplayName("#3 解释层三挂点各出一段人话(mock 带 [MOCK]),置信=数据完备度,落 log")
    void insightThreePoints() {
        long before = externalCalls();
        for (String sub : new String[]{
                InsightService.SCENE_STOCKTAKE, InsightService.SCENE_SETTLEMENT, InsightService.SCENE_ASSET}) {
            Map<String, Object> r = insightService.generate(sub, false);
            assertThat(r.get("text").toString()).contains("[MOCK]");
            assertThat(r.get("llmCallId")).isNotNull();
        }
        assertThat(externalCalls()).isEqualTo(before);
    }

    // ============================== 6. #4 异常侦探 ==============================

    @Test
    @DisplayName("#4 规则侦测销量骤变(前7天≥5件,环比 -60%),归因叙事 mock 落 log")
    void anomalySalesSwing() {
        long before = externalCalls();
        Long pid = seedProductId();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        // 前 7-14 天:10 件(基期);近 7 天:3 件(-70%)
        for (int i = 0; i < 10; i++) {
            insertSale(1L, pid, "1", "正常", now.minusDays(10));
        }
        for (int i = 0; i < 3; i++) {
            insertSale(1L, pid, "1", "正常", now.minusDays(2));
        }
        List<AnomalyService.Anomaly> list = anomalyService.detect();
        AnomalyService.Anomaly swing = list.stream()
                .filter(a -> "sales-swing".equals(a.getType()))
                .findFirst().orElse(null);
        assertThat(swing).as("应侦测到销量骤变").isNotNull();
        assertThat(swing.getSeverity()).isEqualTo("红");

        Map<String, Object> ex = anomalyService.explain(swing, false);
        assertThat(ex.get("text").toString()).isNotBlank();
        assertThat(ex.get("llmCallId")).isNotNull();
        assertThat(externalCalls()).isEqualTo(before);
    }

    // ============================== 7. #7 PDCA 起草 ==============================

    @Test
    @DisplayName("#7 PDCA 起草:接口返回措施+验证指标(target 取入参不自算),落 log,零外呼")
    void pdcaDraft() {
        long before = externalCalls();
        IPdcaDraftService.PdcaDraftResult r = pdcaDraftService.draft(
                IPdcaDraftService.PdcaDraftReq.builder()
                        .bizKey("check:1:售罄率:2026-08")
                        .metricName("售罄率")
                        .actualValue(new BigDecimal("0.35"))
                        .targetValue(new BigDecimal("0.85"))
                        .scope("4楼售卖机")
                        .build());
        assertThat(r.getMeasureTitle()).contains("售罄率");
        assertThat(r.getVerifyMetrics()).isNotEmpty();
        assertThat(r.getVerifyMetrics().get(0).getTarget()).isEqualByComparingTo("0.85");
        assertThat(r.getLlmCallId()).isNotNull();
        assertThat(r.getModel()).startsWith("mock(");
        assertThat(externalCalls()).isEqualTo(before);

        // idempotent:同 bizKey 当日第二次命中缓存
        IPdcaDraftService.PdcaDraftResult r2 = pdcaDraftService.draft(
                IPdcaDraftService.PdcaDraftReq.builder()
                        .bizKey("check:1:售罄率:2026-08")
                        .metricName("售罄率")
                        .actualValue(new BigDecimal("0.35"))
                        .targetValue(new BigDecimal("0.85"))
                        .scope("4楼售卖机")
                        .build());
        assertThat(r2.isCacheHit()).isTrue();
        assertThat(r2.getLlmCallId()).isEqualTo(r.getLlmCallId());
    }

    // ============================== 8. 模型路由 ==============================

    @Test
    @DisplayName("模型路由:默认=§12.2 预填;设置覆盖→effective 变;清空→回默认")
    void modelRouting() {
        assertThat(routeService.resolve(AiScenes.ALIAS)).isEqualTo(AiScenes.ALIAS.getDefaultModel());
        routeService.setModel(AiScenes.ALIAS, "qwen-flash", "测试员");
        assertThat(routeService.resolve(AiScenes.ALIAS)).isEqualTo("qwen-flash");
        assertThat(routeService.allOverrides()).containsEntry("alias", "qwen-flash");
        routeService.setModel(AiScenes.ALIAS, "", "测试员");
        assertThat(routeService.resolve(AiScenes.ALIAS)).isEqualTo(AiScenes.ALIAS.getDefaultModel());
    }

    // ============================== 9. 白名单视图存在性(V1.0.16 迁移) ==============================

    @Test
    @DisplayName("V1.0.16:5 个白名单视图都建成且可查")
    void whitelistViewsExist() {
        for (String v : NlQueryService.WHITELIST_VIEWS) {
            Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM " + v, Integer.class);
            assertThat(c).as(v + " 可查").isNotNull();
        }
    }

    // ============================== 10. #6 安全闸 P1-1:逗号连表 ==============================

    @Test
    @DisplayName("#6 P1-1:逗号连表被拒(白名单外表 + 纯白名单视图都拒),单表仍放行")
    void nlCommaJoinRejected() {
        // 逗号连非白名单业务表:正则数表名漏掉逗号后的表,靠逗号闸拦下(否则会读到用户表)
        assertThatThrownBy(() -> nlQueryService.enforceSafety("SELECT * FROM v_ai_loss, yc_vend_user"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("逗号连表");
        // 两个白名单视图逗号连也拒(强制单表白名单视图,不许隐式交叉连接)
        assertThatThrownBy(() -> nlQueryService.enforceSafety("SELECT * FROM v_ai_loss, v_ai_cash_summary"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("逗号连表");
        // 反例:单表白名单视图仍放行(自动补 LIMIT)
        assertThat(nlQueryService.enforceSafety("SELECT 月份 FROM v_ai_loss").toLowerCase()).contains("limit");
    }

    // ============================== 11. #4 归因 P1-2:只认服务端 key ==============================

    @Test
    @DisplayName("#4 P1-2:归因只收 key,服务端重侦测用自算 metrics;未知 key 拒;confidence_source=computed")
    void anomalyExplainByKeyServerSide() {
        Long pid = seedProductId();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (int i = 0; i < 10; i++) {
            insertSale(1L, pid, "1", "正常", now.minusDays(10));
        }
        for (int i = 0; i < 3; i++) {
            insertSale(1L, pid, "1", "正常", now.minusDays(2));
        }
        AnomalyService.Anomaly swing = anomalyService.detect().stream()
                .filter(a -> "sales-swing".equals(a.getType()))
                .findFirst().orElseThrow(() -> new AssertionError("应侦测到销量骤变"));

        // 入口只收 key:客户端就算塞假 metrics 也进不来
        Map<String, Object> ex = anomalyService.explainByKey(swing.getKey(), false);
        assertThat(ex.get("text").toString()).isNotBlank();
        Long callId = ((Number) ex.get("llmCallId")).longValue();

        // 落库原始数据来自服务端重算(含真实前/近7天件数),置信来源=computed(偏离度真算)
        LlmCallLog log = llmCallLogMapper.selectById(callId);
        assertThat(log.getInputDigest()).contains("prev7dQty").contains("cur7dQty");
        assertThat(log.getConfidenceSource()).isEqualTo("computed");

        // 未知 key 直接拒(拿不到伪造 key 的归因)
        assertThatThrownBy(() -> anomalyService.explainByKey("sales-swing:999999:888888", false))
                .isInstanceOf(BizException.class);
    }

    // ============================== 12. P1-1:置信分来源诚实标注 ==============================

    @Test
    @DisplayName("P1-1:别名=computed(真算相似度),pdca起草=fixed(固定基准)——不宣称真算却写死")
    void confidenceSourceHonest() {
        product("SP-COKE", "可口可乐330ml", "6901234500011");
        pending("可口可乐", "6901234500011");
        aliasSuggestService.suggestAll(false);
        LlmCallLog aliasLog = llmCallLogMapper.selectOne(new LambdaQueryWrapper<LlmCallLog>()
                .eq(LlmCallLog::getScene, AiScenes.ALIAS.getLabel())
                .orderByDesc(LlmCallLog::getId).last("LIMIT 1"));
        assertThat(aliasLog.getConfidenceSource()).isEqualTo("computed");

        pdcaDraftService.draft(IPdcaDraftService.PdcaDraftReq.builder()
                .bizKey("check:9:动销率:conf-test")
                .metricName("动销率")
                .actualValue(new BigDecimal("0.30"))
                .targetValue(new BigDecimal("0.80"))
                .build());
        LlmCallLog pdcaLog = llmCallLogMapper.selectOne(new LambdaQueryWrapper<LlmCallLog>()
                .eq(LlmCallLog::getScene, "PDCA起草:动销率")
                .orderByDesc(LlmCallLog::getId).last("LIMIT 1"));
        assertThat(pdcaLog.getConfidenceSource()).isEqualTo("fixed");
    }
}
