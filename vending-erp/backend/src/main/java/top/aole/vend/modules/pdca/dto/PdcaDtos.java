package top.aole.vend.modules.pdca.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** M4-3 PDCA 改进循环 DTO 集 */
public class PdcaDtos {

    /** 新建/编辑改进任务 */
    @Data
    public static class ItemSaveReq {
        /** 来源环节:补货/采购/选品/定价/钱账/盘点 */
        @NotBlank(message = "来源环节必选")
        private String sourceScene;
        @NotBlank(message = "问题描述必填")
        private String problemDesc;
        @NotBlank(message = "改进措施必填")
        private String measure;
        private Long ownerUser;
        /** 验证指标人话(如"过期报损<¥10/月");不填按结构化三件套自动生成 */
        private String verifyMetric;
        /** 指标键;空=需人工判定 */
        private String metricKey;
        private String metricParam;
        private BigDecimal targetValue;
        /** <= / >= */
        private String compareOp;
        @NotNull(message = "验证日期必填")
        private LocalDate verifyDate;
        private String sourceRefType;
        private Long sourceRefId;
        private Boolean aiDraft;
        /** AI 起草时的调用记录 id(🔬 四件套入口);人工登记为空 */
        private Long llmCallId;
    }

    /** 改进任务行(列表/详情通用) */
    @Data
    public static class ItemRow {
        private Long id;
        private String sourceScene;
        private String problemDesc;
        private String measure;
        private Long ownerUser;
        private String verifyMetric;
        private String metricKey;
        private String metricName;
        private String metricParam;
        private BigDecimal targetValue;
        private String compareOp;
        private BigDecimal baselineValue;
        private BigDecimal verifyValue;
        private String verifyResult;
        private String verifyNote;
        private String verifiedAt;
        private LocalDate verifyDate;
        private String itemStatus;
        private String sourceRefType;
        private Long sourceRefId;
        private Boolean aiDraft;
        private String createTime;
        /** 到期红标:进行中且验证日 <= 今天 */
        private boolean due;
        /** 本周到期(驾驶舱卡口径:今天起 7 天内) */
        private boolean dueThisWeek;
    }

    /** 回查结果(单条) */
    @Data
    public static class RecheckResp {
        private Long itemId;
        /** 通过/未达/需人工判定 */
        private String result;
        /** 指标当前值(需人工判定时为 null) */
        private BigDecimal currentValue;
        private BigDecimal targetValue;
        private String compareOp;
        /** 回查后状态 */
        private String itemStatus;
        private String note;
    }

    /** 批量到期回查汇总 */
    @Data
    public static class RecheckBatchResp {
        private int total;
        private int passed;
        private int failed;
        private int manual;
        private List<RecheckResp> details = new ArrayList<>();
    }

    /** 指标注册表条目(前端下拉用) */
    @Data
    public static class MetricDefRow {
        private String key;
        private String name;
        private String unit;
        /** 参数提示(如"损耗原因(可空=全部)"/"商品ID");null=无参数 */
        private String paramHint;
        /** 推荐比较方向 */
        private String defaultOp;
    }

    /** 指标当前值预览 */
    @Data
    public static class MetricValueResp {
        private String key;
        private String param;
        /** null=取不到(需人工判定) */
        private BigDecimal value;
        private String unit;
        private String note;
    }

    /** 六环节看板:单指标 */
    @Data
    public static class BoardIndicator {
        private String label;
        /** 展示值(带单位人话);取不到时为"—" */
        private String display;
        /** green/amber/red/gray */
        private String level;
    }

    /** 六环节看板:单环节 */
    @Data
    public static class BoardScene {
        private String scene;
        private String icon;
        /** 本期目标(人话) */
        private String target;
        private List<BoardIndicator> indicators = new ArrayList<>();
        /** 环节灯:green/amber/red/gray(=数据不足) */
        private String light;
        /** 进行中的改进任务数 */
        private long openItems;
        /** 红/黄灯时的行动提示 */
        private String hint;
    }

    /** 六环节看板响应 */
    @Data
    public static class BoardResp {
        /** 统计期(本月 yyyy-MM) */
        private String period;
        private List<BoardScene> scenes = new ArrayList<>();
        /** 到期待验证任务数(进行中且验证日<=今天) */
        private long dueCount;
    }

    /** 盘亏向导第 5 步:一键起草(预填,老板确认后才落库) */
    @Data
    public static class DraftResp {
        private Long stocktakeId;
        private String stNo;
        private List<ItemSaveReq> drafts = new ArrayList<>();
        /** 已挂在该盘点单上的改进任务(防重复起草) */
        private List<ItemRow> existing = new ArrayList<>();
    }
}
