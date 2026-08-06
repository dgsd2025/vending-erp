package top.aole.vend.modules.task.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.domain.entity.OpLog;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.task.domain.entity.RoutineTask;
import top.aole.vend.modules.task.domain.entity.TaskInstance;
import top.aole.vend.modules.task.dto.TaskDtos;
import top.aole.vend.modules.task.service.StaffService;
import top.aole.vend.modules.task.service.TaskService;

import javax.validation.Valid;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 任务日历接口(M2-6)。完整路径 /api/v1/task/...
 * 今日任务=懒生成物化(查询即生成,幂等);完成有系统校验;转派留痕;单人模式列合并。
 */
@Api(tags = "任务日历 · 固定任务引擎")
@RestController
@RequestMapping("/v1/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final StaffService staffService;

    // ---------- 视图 ----------

    @ApiOperation("今日任务视图(懒生成+自动校验+逾期红灯+按角色分列/单人合并)")
    @GetMapping("/today")
    public R<TaskDtos.TodayViewResp> today(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(taskService.todayView(date == null ? LocalDate.now() : date));
    }

    @ApiOperation("本周视图(7列格:≤今日为实例,未来日为预告)")
    @GetMapping("/week")
    public R<List<TaskDtos.WeekDay>> week(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        LocalDate today = LocalDate.now();
        LocalDate s = start == null ? today.with(DayOfWeek.MONDAY) : start;
        return R.ok(taskService.weekView(s, today));
    }

    // ---------- 实例操作 ----------

    @ApiOperation("手动补标完成(未过系统校验,黄标留痕)")
    @PostMapping("/instances/{id}/complete")
    public R<TaskInstance> manualComplete(@PathVariable Long id,
                                          @RequestBody(required = false) TaskDtos.ManualCompleteReq req,
                                          @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(taskService.manualComplete(id, req == null ? null : req.getNote(),
                Operators.resolve(userName)));
    }

    @ApiOperation("转派(from/to/时间/原因 op_log 留痕)")
    @PostMapping("/instances/{id}/transfer")
    public R<TaskInstance> transfer(@PathVariable Long id, @Valid @RequestBody TaskDtos.TransferReq req,
                                    @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(taskService.transfer(id, req, Operators.resolve(userName)));
    }

    // ---------- 任务定义 ----------

    @ApiOperation("任务定义列表(含内置种子任务)")
    @GetMapping("/defs")
    public R<List<RoutineTask>> defs() {
        return R.ok(taskService.listDefs());
    }

    @ApiOperation("新建自定义任务")
    @PostMapping("/defs")
    public R<RoutineTask> createDef(@Valid @RequestBody TaskDtos.TaskDefReq req,
                                    @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(taskService.createDef(req, Operators.resolve(userName)));
    }

    @ApiOperation("编辑任务定义(含启用/停用)")
    @PutMapping("/defs/{id}")
    public R<RoutineTask> updateDef(@PathVariable Long id, @Valid @RequestBody TaskDtos.TaskDefReq req,
                                    @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(taskService.updateDef(id, req, Operators.resolve(userName)));
    }

    // ---------- 员工详情(p16) ----------

    @ApiOperation("员工详情总览(角色/近30天任务完成率/经手单据统计)")
    @GetMapping("/staff/{name}/overview")
    public R<TaskDtos.StaffOverviewResp> staffOverview(@PathVariable String name) {
        return R.ok(staffService.overview(name));
    }

    @ApiOperation("员工操作留痕时间线(op_log 按人过滤,分页)")
    @GetMapping("/staff/{name}/op-logs")
    public R<Page<OpLog>> staffOpLogs(@PathVariable String name,
                                      @RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size) {
        return R.ok(staffService.opLogs(name, current, size));
    }
}
