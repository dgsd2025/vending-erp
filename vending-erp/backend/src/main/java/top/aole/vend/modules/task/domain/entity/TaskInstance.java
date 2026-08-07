package top.aole.vend.modules.task.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务实例(yc_vend_task_instance):按日懒生成物化,(task_id, task_date) 唯一保证幂等。
 * 完成两种方式:系统校验(✅自动打勾)/ 手动补标(🟡未过系统校验,留痕);逾期未完成→🔴次日红灯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_task_instance")
public class TaskInstance extends BaseEntity {

    public static final String STATUS_TODO = "待办";
    public static final String STATUS_DONE = "已完成";
    public static final String STATUS_OVERDUE = "逾期";

    public static final String DONE_AUTO = "系统校验";
    public static final String DONE_MANUAL = "手动补标";

    private Long taskId;
    private String taskKey;
    private String taskName;
    private LocalDate taskDate;
    private String assigneeRole;
    private Long assigneeUserId;
    /** 当前责任人(转派后更新,历次转派 op_log 留痕) */
    private String assigneeUserName;
    /** 待办/已完成/逾期 */
    private String instanceStatus;
    private String checkType;
    /** 系统校验/手动补标 */
    private String doneType;
    private LocalDateTime doneTime;
    private String doneBy;
    private String doneNote;
    private Integer transferCount;
}
