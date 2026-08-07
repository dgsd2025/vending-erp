package top.aole.vend.modules.pdca.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 改进任务(yc_vend_action_item):PDCA A 环节唯一落点(§8.4)。
 * 所有环节的"改进"都写这一张表,带 结构化验证指标(指标键+目标值+比较方向)+ 验证日期,
 * 到期自动回查:取指标当前值 vs 目标 → 通过关闭 / 未达升级 / 取不到数需人工判定。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_action_item")
public class ActionItem extends BaseEntity {

    // ---------- 来源环节六枚举(§8.3 六环节 PDCA 总表) ----------
    public static final String SCENE_REPLENISH = "补货";
    public static final String SCENE_PURCHASE = "采购";
    public static final String SCENE_SELECT = "选品";
    public static final String SCENE_PRICE = "定价";
    public static final String SCENE_MONEY = "钱账";
    public static final String SCENE_STOCKTAKE = "盘点";

    // ---------- 状态 ----------
    public static final String ST_OPEN = "进行中";
    public static final String ST_PASSED = "验证通过";
    public static final String ST_ESCALATED = "未见效升级";
    public static final String ST_CLOSED = "已关闭";

    // ---------- 比较方向 ----------
    /** 降到目标以下算通过(损耗额/带回率/缺货时长这类"越低越好") */
    public static final String OP_LE = "<=";
    /** 升到目标以上算通过(动销率/符合率这类"越高越好") */
    public static final String OP_GE = ">=";

    // ---------- 回查结果三分支 ----------
    public static final String VR_PASS = "通过";
    public static final String VR_FAIL = "未达";
    public static final String VR_MANUAL = "需人工判定";

    /** 来源环节:补货/采购/选品/定价/钱账/盘点 */
    private String sourceScene;
    /** 问题描述(如"过期报损集中在面包") */
    private String problemDesc;
    /** 改进措施(如"机内上限24→12") */
    private String measure;
    /** 负责人 */
    private Long ownerUser;
    /** 验证指标(人话展示,如"过期报损<¥10/月") */
    private String verifyMetric;
    /** 验证指标键(PdcaMetricService 注册表);NULL=需人工判定 */
    private String metricKey;
    /** 指标参数(如损耗原因/商品ID) */
    private String metricParam;
    /** 目标值 */
    private BigDecimal targetValue;
    /** 比较方向:<= / >= */
    private String compareOp;
    /** 登记时指标基线值(改进前起点) */
    private BigDecimal baselineValue;
    /** 回查时指标实际值 */
    private BigDecimal verifyValue;
    /** 回查结果:通过/未达/需人工判定 */
    private String verifyResult;
    /** 回查说明 */
    private String verifyNote;
    /** 最近一次回查时间 */
    private LocalDateTime verifiedAt;
    /** 验证日期(通常=下次月盘),到期驾驶舱提醒 */
    private LocalDate verifyDate;
    /** 状态:进行中/验证通过/未见效升级/已关闭 */
    private String itemStatus;
    /** 来源引用类型(盘点单/月度报告/五步向导) */
    private String sourceRefType;
    /** 来源引用ID */
    private Long sourceRefId;
    /** LLM 起草标记(AI 建议→采纳→验证闭环,M4-5 接真网关) */
    private Boolean aiDraft;
    /** AI 调用记录 */
    private Long llmCallId;
}
