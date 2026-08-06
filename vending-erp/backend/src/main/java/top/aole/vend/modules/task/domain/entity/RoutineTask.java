package top.aole.vend.modules.task.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.time.LocalDate;

/**
 * 固定任务定义(yc_vend_routine_task):任务日历引擎的"节拍器"。
 * 铁律#8:任务绑角色(assignee_role),角色绑人(user_role);指定人可空=角色内默认。
 * 周期用结构化枚举(cycle_type + cycle_value),cycle_rule 只作人话展示。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_routine_task")
public class RoutineTask extends BaseEntity {

    public static final String CYCLE_DAILY = "每日";
    public static final String CYCLE_WEEKLY = "每周";
    public static final String CYCLE_MONTHLY = "每月";
    public static final String CYCLE_EVERY_N_DAYS = "每N天";

    /** 系统校验:当日存在成功导入批次 */
    public static final String CHECK_IMPORT_BATCH = "IMPORT_BATCH";
    /** 系统校验:当日有已确认出库上架单,或当日补货建议全处理 */
    public static final String CHECK_REPLENISH = "REPLENISH";
    /** 系统校验:当月存在已完成盘点单 */
    public static final String CHECK_STOCKTAKE = "STOCKTAKE";
    /** 系统校验(M3-9 七律修复):当月存在已核销/已核对平台结算单 */
    public static final String CHECK_SETTLEMENT = "SETTLEMENT";
    /** 系统校验(M3-9 七律修复):当月存在已完成钱盘核对记录 */
    public static final String CHECK_CASH_CHECK = "CASH_CHECK";

    private String taskKey;
    private String taskName;
    /** 人话周期(每日/每3天/每月1日),展示用 */
    private String cycleRule;
    /** 周期类型:每日/每周/每月/每N天 */
    private String cycleType;
    /** 周期值:每周N(1-7,周一=1)/每月N日/每N天的N */
    private Integer cycleValue;
    /** 每N天的锚点日 */
    private LocalDate anchorDate;
    /** 责任角色:BOSS/FINANCE/REPLENISH/CLERK */
    private String assigneeRole;
    /** 指定责任人(可空=角色内默认) */
    private Long assigneeUserId;
    private String assigneeUserName;
    /** 系统校验类型(NULL=纯手动任务) */
    private String checkType;
    /** 校验规则人话说明 */
    private String autoCheckRule;
    /** 是否启用 */
    private Integer taskEnabled;

    // ---- 旧占位列(V1.0.0 设计,引擎不再使用,保留兼容) ----
    private String ownerRole;
    private String duePeriod;
    private String taskStatus;
    private String lastResult;
}
