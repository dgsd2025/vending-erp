package top.aole.vend.modules.task.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.task.domain.entity.RoutineTask;
import top.aole.vend.modules.task.domain.entity.TaskInstance;
import top.aole.vend.modules.task.domain.entity.UserRole;
import top.aole.vend.modules.task.dto.TaskDtos;
import top.aole.vend.modules.task.mapper.RoutineTaskMapper;
import top.aole.vend.modules.task.mapper.TaskInstanceMapper;
import top.aole.vend.modules.task.mapper.TaskQueryMapper;
import top.aole.vend.modules.task.mapper.UserRoleMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 固定任务引擎(M2-6):
 * ① 懒生成物化——查询当日视图时按定义生成当日实例((task_id, task_date) 唯一键幂等),无需 cron 进程;
 * ② 系统校验自动打勾——完成必有系统校验(导数据=真收到文件),手动补标只能拿🟡黄标留痕;
 * ③ 逾期未完成→次日红灯;④ 转派 from/to/时间/原因 op_log 留痕;
 * ⑤ 单人模式:所有角色映射到同一人→列合并,加人自动拆列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    /** 角色码→人话(列头/员工页展示) */
    public static final Map<String, String> ROLE_NAMES = new LinkedHashMap<String, String>() {{
        put(UserRole.ROLE_BOSS, "👑 老板");
        put(UserRole.ROLE_FINANCE, "💼 财务");
        put(UserRole.ROLE_REPLENISH, "🚚 补货员");
        put(UserRole.ROLE_CLERK, "✏️ 录单员");
    }};

    private final RoutineTaskMapper routineTaskMapper;
    private final TaskInstanceMapper taskInstanceMapper;
    private final UserRoleMapper userRoleMapper;
    private final TaskQueryMapper taskQueryMapper;
    private final OpLogService opLogService;

    // =====================================================================
    // 周期判定
    // =====================================================================

    /** 定义在某日是否到期(每月N日超月长收敛到月末,如 2/30 → 2/28) */
    public boolean isDue(RoutineTask task, LocalDate date) {
        int v = task.getCycleValue() == null ? 1 : task.getCycleValue();
        switch (task.getCycleType() == null ? "" : task.getCycleType()) {
            case RoutineTask.CYCLE_DAILY:
                return true;
            case RoutineTask.CYCLE_WEEKLY:
                return date.getDayOfWeek().getValue() == Math.min(Math.max(v, 1), 7);
            case RoutineTask.CYCLE_MONTHLY:
                return date.getDayOfMonth() == Math.min(Math.max(v, 1), date.lengthOfMonth());
            case RoutineTask.CYCLE_EVERY_N_DAYS: {
                LocalDate anchor = task.getAnchorDate() != null ? task.getAnchorDate()
                        : (task.getCreateTime() != null ? task.getCreateTime().toLocalDate() : date);
                long days = ChronoUnit.DAYS.between(anchor, date);
                return days >= 0 && days % Math.max(v, 1) == 0;
            }
            default:
                return false;
        }
    }

    // =====================================================================
    // 懒生成物化(幂等)
    // =====================================================================

    /** 按定义物化某日实例:已存在(唯一键)则跳过——幂等,可反复调用 */
    @Transactional
    public int materializeDay(LocalDate date) {
        List<RoutineTask> defs = routineTaskMapper.selectList(new LambdaQueryWrapper<RoutineTask>()
                .eq(RoutineTask::getTaskEnabled, 1));
        int created = 0;
        for (RoutineTask def : defs) {
            if (!isDue(def, date)) {
                continue;
            }
            Long exists = taskInstanceMapper.selectCount(new LambdaQueryWrapper<TaskInstance>()
                    .eq(TaskInstance::getTaskId, def.getId())
                    .eq(TaskInstance::getTaskDate, date));
            if (exists != null && exists > 0) {
                continue;
            }
            TaskInstance inst = new TaskInstance();
            inst.setTaskId(def.getId());
            inst.setTaskKey(def.getTaskKey());
            inst.setTaskName(def.getTaskName());
            inst.setTaskDate(date);
            inst.setAssigneeRole(def.getAssigneeRole());
            inst.setCheckType(def.getCheckType());
            inst.setInstanceStatus(TaskInstance.STATUS_TODO);
            inst.setTransferCount(0);
            resolveAssignee(def, inst);
            try {
                taskInstanceMapper.insert(inst);
                created++;
            } catch (DuplicateKeyException e) {
                // 并发生成撞唯一键=已有别人生成,幂等吞掉
                log.debug("任务实例已存在,跳过:taskId={} date={}", def.getId(), date);
            }
        }
        return created;
    }

    /** 责任人解析:定义指定人 > 角色内默认(该角色第一个在岗的人) > 空(只挂角色) */
    private void resolveAssignee(RoutineTask def, TaskInstance inst) {
        if (StrUtil.isNotBlank(def.getAssigneeUserName())) {
            inst.setAssigneeUserId(def.getAssigneeUserId());
            inst.setAssigneeUserName(def.getAssigneeUserName());
            return;
        }
        if (StrUtil.isBlank(def.getAssigneeRole())) {
            return;
        }
        UserRole holder = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRoleCode, def.getAssigneeRole())
                        .eq(UserRole::getStatus, 1)
                        .orderByAsc(UserRole::getId))
                .stream().findFirst().orElse(null);
        if (holder != null) {
            inst.setAssigneeUserId(holder.getUserId());
            inst.setAssigneeUserName(holder.getUserName());
        }
    }

    // =====================================================================
    // 系统校验(完成必有系统校验,不是打勾就算)
    // =====================================================================

    /** 跑某类系统校验:通过=true */
    public boolean runCheck(String checkType, LocalDate date) {
        if (StrUtil.isBlank(checkType)) {
            return false;
        }
        switch (checkType) {
            case RoutineTask.CHECK_IMPORT_BATCH:
                return taskQueryMapper.importBatchOk(date) > 0;
            case RoutineTask.CHECK_REPLENISH: {
                if (taskQueryMapper.transferDocOk(date) > 0) {
                    return true;
                }
                int total = taskQueryMapper.replenishPlanTotal(date);
                return total > 0 && taskQueryMapper.replenishPlanOpen(date) == 0;
            }
            case RoutineTask.CHECK_STOCKTAKE:
                return taskQueryMapper.stocktakeOk(date.format(DateTimeFormatter.ofPattern("yyyy-MM"))) > 0;
            case RoutineTask.CHECK_SETTLEMENT:
                return taskQueryMapper.settlementOk(date.format(DateTimeFormatter.ofPattern("yyyy-MM"))) > 0;
            case RoutineTask.CHECK_CASH_CHECK:
                return taskQueryMapper.cashCheckOk(date.format(DateTimeFormatter.ofPattern("yyyy-MM"))) > 0;
            default:
                return false;
        }
    }

    /**
     * 按校验类型对所有未完成实例跑系统校验(M3-9 七律修复:业务收口主动触发,不等日历页懒校验)。
     * 复用 {@link #runCheck} 同一把尺子;钱盘完成(CashCheckService.finish)调 CASH_CHECK。
     *
     * @return 自动打勾的实例数
     */
    @Transactional
    public int autoCheckByType(String checkType) {
        if (StrUtil.isBlank(checkType)) {
            return 0;
        }
        List<TaskInstance> open = taskInstanceMapper.selectList(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getCheckType, checkType)
                .ne(TaskInstance::getInstanceStatus, TaskInstance.STATUS_DONE));
        int passed = 0;
        for (TaskInstance inst : open) {
            if (runCheck(inst.getCheckType(), inst.getTaskDate())) {
                inst.setInstanceStatus(TaskInstance.STATUS_DONE);
                inst.setDoneType(TaskInstance.DONE_AUTO);
                inst.setDoneTime(LocalDateTime.now());
                inst.setDoneBy("系统");
                taskInstanceMapper.updateById(inst);
                passed++;
            }
        }
        return passed;
    }

    /** 对某日所有未完成实例跑系统校验,通过的自动打勾(done_type=系统校验 ✅) */
    @Transactional
    public int autoCheckDay(LocalDate date) {
        List<TaskInstance> open = taskInstanceMapper.selectList(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskDate, date)
                .ne(TaskInstance::getInstanceStatus, TaskInstance.STATUS_DONE));
        int passed = 0;
        for (TaskInstance inst : open) {
            if (runCheck(inst.getCheckType(), inst.getTaskDate())) {
                inst.setInstanceStatus(TaskInstance.STATUS_DONE);
                inst.setDoneType(TaskInstance.DONE_AUTO);
                inst.setDoneTime(LocalDateTime.now());
                inst.setDoneBy("系统");
                taskInstanceMapper.updateById(inst);
                passed++;
            }
        }
        return passed;
    }

    /**
     * 把今日之前仍"待办"的实例标为逾期(次日红灯)。
     *
     * 盲审 P1-2:定罪前先跑**对应日期**的系统校验——补物化的过去日实例(比如周一干了活但
     * 没人打开过日历,周二补生成)若当日校验能过(活其实干了),按"系统校验✅"补完成;
     * 没过才标逾期。逾期和完成用同一把尺子,不冤枉干了活的员工。
     *
     * @return 实际标成逾期的实例数(校验补过的不算)
     */
    @Transactional
    public int markOverdue(LocalDate today) {
        List<TaskInstance> stale = taskInstanceMapper.selectList(new LambdaQueryWrapper<TaskInstance>()
                .lt(TaskInstance::getTaskDate, today)
                .eq(TaskInstance::getInstanceStatus, TaskInstance.STATUS_TODO));
        int overdue = 0;
        for (TaskInstance inst : stale) {
            if (runCheck(inst.getCheckType(), inst.getTaskDate())) {
                inst.setInstanceStatus(TaskInstance.STATUS_DONE);
                inst.setDoneType(TaskInstance.DONE_AUTO);
                inst.setDoneTime(LocalDateTime.now());
                inst.setDoneBy("系统");
            } else {
                inst.setInstanceStatus(TaskInstance.STATUS_OVERDUE);
                overdue++;
            }
            taskInstanceMapper.updateById(inst);
        }
        return overdue;
    }

    // =====================================================================
    // 视图
    // =====================================================================

    /** 单人模式:在岗映射的去重人名 ≤1 → 列合并 */
    public boolean isSingleUserMode() {
        List<UserRole> actives = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getStatus, 1));
        return actives.stream().map(UserRole::getUserName)
                .filter(StrUtil::isNotBlank).distinct().count() <= 1;
    }

    /** 未完成实例补配责任人:实例生成时角色还没配人,之后配上了→按角色回填(转派过/已完成的不动) */
    @Transactional
    public int backfillAssignees(LocalDate date) {
        List<TaskInstance> blanks = taskInstanceMapper.selectList(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskDate, date)
                .ne(TaskInstance::getInstanceStatus, TaskInstance.STATUS_DONE)
                .isNull(TaskInstance::getAssigneeUserName));
        int filled = 0;
        for (TaskInstance inst : blanks) {
            if ((inst.getTransferCount() != null && inst.getTransferCount() > 0)
                    || StrUtil.isBlank(inst.getAssigneeRole())) {
                continue;
            }
            UserRole holder = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getRoleCode, inst.getAssigneeRole())
                            .eq(UserRole::getStatus, 1)
                            .orderByAsc(UserRole::getId))
                    .stream().findFirst().orElse(null);
            if (holder != null) {
                inst.setAssigneeUserId(holder.getUserId());
                inst.setAssigneeUserName(holder.getUserName());
                taskInstanceMapper.updateById(inst);
                filled++;
            }
        }
        return filled;
    }

    /** 今日视图:物化 + 补配责任人 + 逾期标记 + 自动校验 + 分列(单人合并) */
    @Transactional
    public TaskDtos.TodayViewResp todayView(LocalDate date) {
        materializeDay(date);
        backfillAssignees(date);
        markOverdue(date);
        autoCheckDay(date);

        List<TaskInstance> instances = taskInstanceMapper.selectList(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskDate, date)
                .orderByAsc(TaskInstance::getId));
        List<TaskInstance> overdue = taskInstanceMapper.selectList(new LambdaQueryWrapper<TaskInstance>()
                .lt(TaskInstance::getTaskDate, date)
                .eq(TaskInstance::getInstanceStatus, TaskInstance.STATUS_OVERDUE)
                .orderByDesc(TaskInstance::getTaskDate)
                .last("LIMIT 30"));

        TaskDtos.TodayViewResp resp = new TaskDtos.TodayViewResp();
        resp.setDate(date);
        resp.setInstances(instances);
        resp.setOverdue(overdue);
        resp.setTodoCount((int) instances.stream()
                .filter(i -> !TaskInstance.STATUS_DONE.equals(i.getInstanceStatus())).count());
        resp.setDoneCount(instances.size() - resp.getTodoCount());

        boolean single = isSingleUserMode();
        resp.setSingleUserMode(single);
        List<TaskDtos.RoleColumn> columns = new ArrayList<>();
        if (single) {
            TaskDtos.RoleColumn col = new TaskDtos.RoleColumn();
            col.setRoleCode("ALL");
            String only = instances.stream().map(TaskInstance::getAssigneeUserName)
                    .filter(StrUtil::isNotBlank).findFirst().orElse(null);
            col.setTitle(only != null ? "全部任务 · " + only + "(单人模式)" : "全部任务(单人模式)");
            col.setInstances(instances);
            columns.add(col);
        } else {
            Map<String, List<TaskInstance>> byRole = instances.stream().collect(
                    Collectors.groupingBy(i -> StrUtil.blankToDefault(i.getAssigneeRole(), "OTHER"),
                            LinkedHashMap::new, Collectors.toList()));
            // 固定角色顺序在前,未知角色殿后
            for (Map.Entry<String, String> e : ROLE_NAMES.entrySet()) {
                if (byRole.containsKey(e.getKey())) {
                    columns.add(buildColumn(e.getKey(), byRole.remove(e.getKey())));
                }
            }
            byRole.forEach((role, list) -> columns.add(buildColumn(role, list)));
        }
        resp.setColumns(columns);
        return resp;
    }

    private TaskDtos.RoleColumn buildColumn(String roleCode, List<TaskInstance> list) {
        TaskDtos.RoleColumn col = new TaskDtos.RoleColumn();
        col.setRoleCode(roleCode);
        String names = list.stream().map(TaskInstance::getAssigneeUserName)
                .filter(StrUtil::isNotBlank).distinct().collect(Collectors.joining("/"));
        String roleName = ROLE_NAMES.getOrDefault(roleCode, roleCode);
        col.setTitle(StrUtil.isNotBlank(names) ? roleName + " · " + names : roleName + " · 未配人");
        col.setInstances(list);
        return col;
    }

    /** 本周视图:≤今日的物化返回实例,未来日只返回预告(不落库) */
    @Transactional
    public List<TaskDtos.WeekDay> weekView(LocalDate start, LocalDate today) {
        // 先把 ≤今日 的日期全部物化 + 标逾期 + 跑今日校验,再取数——否则刚补生成的过去日会以"待办"示人
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            if (!d.isAfter(today)) {
                materializeDay(d);
            }
        }
        markOverdue(today);
        autoCheckDay(today);

        List<TaskDtos.WeekDay> days = new ArrayList<>();
        List<RoutineTask> defs = routineTaskMapper.selectList(new LambdaQueryWrapper<RoutineTask>()
                .eq(RoutineTask::getTaskEnabled, 1));
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            TaskDtos.WeekDay day = new TaskDtos.WeekDay();
            day.setDate(d);
            day.setFuture(d.isAfter(today));
            if (!d.isAfter(today)) {
                day.setInstances(taskInstanceMapper.selectList(new LambdaQueryWrapper<TaskInstance>()
                        .eq(TaskInstance::getTaskDate, d).orderByAsc(TaskInstance::getId)));
                day.setPreview(new ArrayList<>());
            } else {
                day.setInstances(new ArrayList<>());
                day.setPreview(defs.stream().filter(t -> isDue(t, d))
                        .map(RoutineTask::getTaskName).collect(Collectors.toList()));
            }
            days.add(day);
        }
        return days;
    }

    // =====================================================================
    // 手动补标 / 转派(op_log 留痕)
    // =====================================================================

    /** 手动补标:拿不到系统校验的🟡黄标完成,必留痕 */
    @Transactional
    public TaskInstance manualComplete(Long instanceId, String note, String operator) {
        TaskInstance inst = mustGet(instanceId);
        if (TaskInstance.STATUS_DONE.equals(inst.getInstanceStatus())) {
            throw new BizException("任务已完成,无需重复补标");
        }
        Map<String, Object> before = snapshot(inst);
        inst.setInstanceStatus(TaskInstance.STATUS_DONE);
        inst.setDoneType(TaskInstance.DONE_MANUAL);
        inst.setDoneTime(LocalDateTime.now());
        inst.setDoneBy(operator);
        inst.setDoneNote(StrUtil.blankToDefault(note, "手动确认,未过系统校验"));
        taskInstanceMapper.updateById(inst);
        opLogService.record(operator, "手动补标", "task_instance", inst.getId(), before, snapshot(inst));
        return inst;
    }

    /** 转派:from/to/时间/原因全留痕 */
    @Transactional
    public TaskInstance transfer(Long instanceId, TaskDtos.TransferReq req, String operator) {
        TaskInstance inst = mustGet(instanceId);
        if (TaskInstance.STATUS_DONE.equals(inst.getInstanceStatus())) {
            throw new BizException("任务已完成,不能转派");
        }
        String from = StrUtil.blankToDefault(inst.getAssigneeUserName(), "(未配人)");
        if (Objects.equals(from, req.getToUserName())) {
            throw new BizException("转派对象与当前责任人相同");
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("from", from);
        detail.put("to", req.getToUserName());
        detail.put("reason", StrUtil.blankToDefault(req.getReason(), "(未填原因)"));
        detail.put("taskName", inst.getTaskName());
        detail.put("taskDate", String.valueOf(inst.getTaskDate()));

        inst.setAssigneeUserId(req.getToUserId());
        inst.setAssigneeUserName(req.getToUserName());
        inst.setTransferCount((inst.getTransferCount() == null ? 0 : inst.getTransferCount()) + 1);
        taskInstanceMapper.updateById(inst);
        opLogService.record(operator, "转派", "task_instance", inst.getId(), null, detail);
        return inst;
    }

    private TaskInstance mustGet(Long id) {
        TaskInstance inst = taskInstanceMapper.selectById(id);
        if (inst == null) {
            throw new BizException("任务实例不存在:" + id);
        }
        return inst;
    }

    private Map<String, Object> snapshot(TaskInstance inst) {
        Map<String, Object> m = new HashMap<>();
        m.put("instanceStatus", inst.getInstanceStatus());
        m.put("doneType", inst.getDoneType());
        m.put("doneNote", inst.getDoneNote());
        m.put("assigneeUserName", inst.getAssigneeUserName());
        return m;
    }

    // =====================================================================
    // 任务定义 CRUD(自定义任务)
    // =====================================================================

    public List<RoutineTask> listDefs() {
        return routineTaskMapper.selectList(new LambdaQueryWrapper<RoutineTask>()
                .orderByAsc(RoutineTask::getId));
    }

    @Transactional
    public RoutineTask createDef(TaskDtos.TaskDefReq req, String operator) {
        RoutineTask def = new RoutineTask();
        def.setTaskKey("custom_" + System.currentTimeMillis());
        applyDefReq(def, req);
        def.setAnchorDate(LocalDate.now());
        routineTaskMapper.insert(def);
        opLogService.record(operator, "新建", "routine_task", def.getId(), null, req);
        return def;
    }

    @Transactional
    public RoutineTask updateDef(Long id, TaskDtos.TaskDefReq req, String operator) {
        RoutineTask def = routineTaskMapper.selectById(id);
        if (def == null) {
            throw new BizException("任务定义不存在:" + id);
        }
        TaskDtos.TaskDefReq before = new TaskDtos.TaskDefReq();
        before.setTaskName(def.getTaskName());
        before.setCycleType(def.getCycleType());
        before.setCycleValue(def.getCycleValue());
        before.setAssigneeRole(def.getAssigneeRole());
        before.setAssigneeUserName(def.getAssigneeUserName());
        before.setCheckType(def.getCheckType());
        before.setEnabled(def.getTaskEnabled() != null && def.getTaskEnabled() == 1);
        applyDefReq(def, req);
        routineTaskMapper.updateById(def);
        opLogService.record(operator, "修改", "routine_task", def.getId(), before, req);
        return def;
    }

    private void applyDefReq(RoutineTask def, TaskDtos.TaskDefReq req) {
        def.setTaskName(req.getTaskName());
        def.setCycleType(req.getCycleType());
        def.setCycleValue(req.getCycleValue() == null ? 1 : req.getCycleValue());
        def.setAssigneeRole(req.getAssigneeRole());
        def.setAssigneeUserId(req.getAssigneeUserId());
        def.setAssigneeUserName(req.getAssigneeUserName());
        def.setCheckType(req.getCheckType());
        def.setTaskEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);
        def.setCycleRule(humanCycle(def));
        def.setOwnerRole(req.getAssigneeRole());
    }

    private String humanCycle(RoutineTask def) {
        int v = def.getCycleValue() == null ? 1 : def.getCycleValue();
        switch (def.getCycleType() == null ? "" : def.getCycleType()) {
            case RoutineTask.CYCLE_DAILY:
                return "每日";
            case RoutineTask.CYCLE_WEEKLY:
                return "每周" + "一二三四五六日".charAt(Math.min(Math.max(v, 1), 7) - 1);
            case RoutineTask.CYCLE_MONTHLY:
                return "每月" + v + "日";
            case RoutineTask.CYCLE_EVERY_N_DAYS:
                return "每" + v + "天";
            default:
                return def.getCycleType();
        }
    }
}
