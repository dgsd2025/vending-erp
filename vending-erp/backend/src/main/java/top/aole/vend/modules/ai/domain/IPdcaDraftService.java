package top.aole.vend.modules.ai.domain;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接入点#7:PDCA 改进建议起草(给 pdca 票的对接接口)。
 *
 * 分工:pdca 模块侦测 C 指标红灯 → 调本接口 draft() 拿"措施 + 验证指标"草案(JSON)→
 * 用户一键采纳 → pdca 侧建 action_item(本 AI 模块不建 action_item,只起草)。
 * 铁律:LLM 只起草文案,红灯判定/指标口径全由 pdca 规则引擎出;走 LlmGateway 落四件套 + 24h idempotent。
 *
 * 本票交付:接口 + MockPdcaDraftService 实现;pdca 侧注入 IPdcaDraftService 即可用。
 */
public interface IPdcaDraftService {

    /**
     * 起草改进建议。
     *
     * @param req 红灯上下文(pdca 侧规则引擎已算好指标)
     * @return 措施 + 验证指标草案
     */
    PdcaDraftResult draft(PdcaDraftReq req);

    /** 入参:C 指标红灯的规则事实(数字全部由 pdca 规则引擎给,AI 不算) */
    @Data
    @Builder
    class PdcaDraftReq {
        /** 幂等业务键(pdca 侧红灯的稳定标识,如 "check:machineId:metric:period") */
        private String bizKey;
        /** 指标名(如 售罄率/损耗率/动销率) */
        private String metricName;
        /** 实际值 */
        private BigDecimal actualValue;
        /** 目标/阈值 */
        private BigDecimal targetValue;
        /** 红灯范围描述(哪台机/哪个品类/哪个月) */
        private String scope;
        /** 补充上下文(自由文本,pdca 侧拼) */
        private String context;
        /** 跳过当日缓存重新起草 */
        private boolean force;
    }

    /** 出参:一键采纳时 pdca 侧据此建 action_item */
    @Data
    @Builder
    class PdcaDraftResult {
        /** 改进措施标题(建议 action_item.title) */
        private String measureTitle;
        /** 措施详述 */
        private String measureDetail;
        /** 验证指标(采纳后到期回查用) */
        private List<VerifyMetric> verifyMetrics;
        /** 建议期限(天) */
        private Integer suggestDueDays;
        /** llm_call_log id(🔬 四件套入口) */
        private Long llmCallId;
        /** 是否当日缓存命中 */
        private boolean cacheHit;
        /** 生效模型名(mock 时为 mock(...)) */
        private String model;
    }

    /** 验证指标:名字 + 目标值 + 口径说明 */
    @Data
    @Builder
    class VerifyMetric {
        private String name;
        private BigDecimal target;
        private String note;
    }
}
