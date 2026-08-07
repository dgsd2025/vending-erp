package top.aole.vend.modules.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.basedata.domain.entity.OpLog;
import top.aole.vend.modules.basedata.infrastructure.mapper.OpLogMapper;
import top.aole.vend.modules.task.domain.entity.TaskInstance;
import top.aole.vend.modules.task.domain.entity.UserRole;
import top.aole.vend.modules.task.dto.TaskDtos;
import top.aole.vend.modules.task.mapper.TaskInstanceMapper;
import top.aole.vend.modules.task.mapper.TaskQueryMapper;
import top.aole.vend.modules.task.mapper.UserRoleMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 员工详情(p16 体检报告式):人也是一等公民——干了什么、干得准不准、手上压着什么,一页看清。
 * 定位是工作画像+交接备忘,数据全部来自任务实例与 op_log 留痕,不需要额外填报。
 */
@Service
@RequiredArgsConstructor
public class StaffService {

    private final UserRoleMapper userRoleMapper;
    private final TaskInstanceMapper taskInstanceMapper;
    private final TaskQueryMapper taskQueryMapper;
    private final OpLogMapper opLogMapper;

    public TaskDtos.StaffOverviewResp overview(String userName) {
        TaskDtos.StaffOverviewResp resp = new TaskDtos.StaffOverviewResp();
        resp.setUserName(userName);

        List<UserRole> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserName, userName));
        resp.setRoles(roles.stream().map(UserRole::getRoleCode).distinct().collect(Collectors.toList()));
        resp.setActive(roles.stream().anyMatch(r -> r.getStatus() != null && r.getStatus() == 1));

        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(30);
        List<TaskInstance> insts = taskInstanceMapper.selectList(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getAssigneeUserName, userName)
                .ge(TaskInstance::getTaskDate, from));
        resp.setTaskTotal30d(insts.size());
        resp.setTaskDone30d((int) insts.stream()
                .filter(i -> TaskInstance.STATUS_DONE.equals(i.getInstanceStatus())).count());
        resp.setTaskAutoDone30d((int) insts.stream()
                .filter(i -> TaskInstance.DONE_AUTO.equals(i.getDoneType())).count());
        resp.setTaskManualDone30d((int) insts.stream()
                .filter(i -> TaskInstance.DONE_MANUAL.equals(i.getDoneType())).count());
        resp.setTaskOverdue30d((int) insts.stream()
                .filter(i -> TaskInstance.STATUS_OVERDUE.equals(i.getInstanceStatus())).count());
        resp.setCompletionRate(insts.isEmpty() ? null
                : Math.round(resp.getTaskDone30d() * 1000.0 / insts.size()) / 10.0);
        resp.setTodayTodo((int) insts.stream()
                .filter(i -> today.equals(i.getTaskDate())
                        && !TaskInstance.STATUS_DONE.equals(i.getInstanceStatus())).count());

        resp.setOpLogStats(taskQueryMapper.opLogStatsByUser(userName));
        resp.setOpLogTotal(resp.getOpLogStats().stream()
                .mapToLong(m -> ((Number) m.getOrDefault("cnt", 0)).longValue()).sum());
        return resp;
    }

    /** 操作留痕时间线(op_log 按人过滤,分页) */
    public Page<OpLog> opLogs(String userName, long current, long size) {
        return opLogMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<OpLog>()
                        .eq(OpLog::getUserName, userName)
                        .orderByDesc(OpLog::getId));
    }
}
