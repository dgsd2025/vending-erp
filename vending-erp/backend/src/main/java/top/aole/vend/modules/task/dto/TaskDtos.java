package top.aole.vend.modules.task.dto;

import lombok.Data;
import top.aole.vend.modules.task.domain.entity.TaskInstance;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 任务日历 DTO 集(M2-6)。
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** 今日任务视图:实例列表 + 按角色分列(单人模式合并) + 逾期红灯 */
    @Data
    public static class TodayViewResp {
        private LocalDate date;
        /** 单人模式:所有角色映射到同一人(或没配人)→ 前端列合并显示 */
        private boolean singleUserMode;
        /** 分列:role → 实例列表;单人模式时只有一列 key="ALL" */
        private List<RoleColumn> columns;
        /** 今日全部实例(平铺,含已完成) */
        private List<TaskInstance> instances;
        /** 逾期未完成(task_date < 今日,次日红灯) */
        private List<TaskInstance> overdue;
        private int todoCount;
        private int doneCount;
    }

    @Data
    public static class RoleColumn {
        /** 角色码,单人合并时为 ALL */
        private String roleCode;
        /** 列头显示:角色名(+当前担任的人) */
        private String title;
        private List<TaskInstance> instances;
    }

    /** 本周视图:7 天,每天=已物化实例(≤今日)或预告(>今日,不落库) */
    @Data
    public static class WeekDay {
        private LocalDate date;
        /** 今日/过去/未来 */
        private boolean future;
        private List<TaskInstance> instances;
        /** 未来日预告(定义名列表,不生成实例) */
        private List<String> preview;
    }

    /** 手动补标 */
    @Data
    public static class ManualCompleteReq {
        /** 补标原因(留痕:手动确认,未过系统校验) */
        private String note;
    }

    /** 转派 */
    @Data
    public static class TransferReq {
        @NotBlank(message = "转派对象不能为空")
        private String toUserName;
        private Long toUserId;
        private String reason;
    }

    /** 新建/编辑任务定义 */
    @Data
    public static class TaskDefReq {
        @NotBlank(message = "任务名不能为空")
        private String taskName;
        /** 每日/每周/每月/每N天 */
        @NotBlank(message = "周期类型不能为空")
        private String cycleType;
        private Integer cycleValue;
        private String assigneeRole;
        private Long assigneeUserId;
        private String assigneeUserName;
        /** IMPORT_BATCH/REPLENISH/STOCKTAKE/null=纯手动 */
        private String checkType;
        private Boolean enabled;
    }

    /** 员工详情(p16 体检报告式) */
    @Data
    public static class StaffOverviewResp {
        private String userName;
        private List<String> roles;
        /** 在岗=user_role 有 status=1 的映射 */
        private boolean active;
        /** 近30天任务实例统计 */
        private int taskTotal30d;
        private int taskDone30d;
        private int taskAutoDone30d;
        private int taskManualDone30d;
        private int taskOverdue30d;
        /** 完成率(%),无任务为 null */
        private Double completionRate;
        /** 今日待办数 */
        private int todayTodo;
        /** 经手单据统计:target_type → 次数 */
        private List<Map<String, Object>> opLogStats;
        private long opLogTotal;
    }
}
