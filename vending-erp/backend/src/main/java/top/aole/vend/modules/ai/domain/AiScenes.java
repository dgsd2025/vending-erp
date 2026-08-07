package top.aole.vend.modules.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 十二个 AI 接入点注册表(调研 §12.1,按价值排序)。
 * 每个接入点:路由键(设置中心可独立换模型,config key = ai.model.<key>)+ §12.2 默认模型预填 + 期数。
 * 总原则:规则引擎出数字,LLM 只做解释/抽取/起草;未配 key 全走 mock,零外呼。
 */
@Getter
@AllArgsConstructor
public enum AiScenes {

    ALIAS("alias", "别名归集", "#1 SKU别名自动归集:候选召回+复核置信度,人只点确认", "deepseek-v3", 1, true),
    OCR("ocr", "凭证识别", "#2 转账截图/平台账单 → 提取金额/日期/对方,预填表单", "qwen-vl-plus", 1, true),
    EXPLAIN("explain", "解释层", "#3 补货/盘点差异/结算两差/资产环比 的一句人话解读", "deepseek-v3", 1, true),
    ANOMALY("anomaly", "异常侦探", "#4 销量骤变/盘差/结算差异 → 归因叙事(规则侦测出清单)", "deepseek-v3", 2, true),
    MONTHLY("monthly", "月报起草", "#5 月度经营分析报告全文(长上下文喂整月数据)【月报票实现】", "kimi-k2", 2, false),
    NLQUERY("nlquery", "NL查数", "#6 自然语言→SQL(只读白名单视图+LIMIT),实验性", "deepseek-v3", 2, true),
    PDCA("pdca", "PDCA起草", "#7 C指标红灯→起草改进措施+验证指标,一键采纳", "qwen-plus", 2, true),
    PICK("pick", "选品推荐", "#8 淘汰腾出货道→推荐新品【三期不做】", "qwen-plus", 3, false),
    IMPORT_FIX("importfix", "导入自愈", "#9 厂家改模板→猜新列映射【三期不做】", "deepseek-v3", 3, false),
    BARGAIN("bargain", "议价助手", "#10 价格历史+市场价→议价要点【三期不做】", "qwen-max", 3, false),
    PHOTO_COUNT("photocount", "拍照盘点", "#11 拍货道照片→视觉清点【三期不做】", "qwen-vl-plus", 3, false),
    CHAT_OPS("chatops", "对话入口", "#12 对话式操作(function call 建单)【三期不做】", "kimi-k2", 3, false);

    /** 路由键(config key 后缀 / idempotent key 前缀) */
    private final String key;
    /** 中文名(= llm_call_log.scene 主场景;解释层细分场景形如 explain:stocktake-diff) */
    private final String label;
    private final String description;
    /** §12.2 默认模型(设置中心预填,可改) */
    private final String defaultModel;
    /** 期数:1/2/3 */
    private final int phase;
    /** 本票(M4-5)是否已挂接:一期#1/#2/#3 + 二期#4/#6/#7 */
    private final boolean wired;

    public static AiScenes byKey(String key) {
        return Arrays.stream(values())
                .filter(s -> s.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知 AI 接入点:" + key));
    }

    public static List<AiScenes> all() {
        return Arrays.asList(values());
    }
}
