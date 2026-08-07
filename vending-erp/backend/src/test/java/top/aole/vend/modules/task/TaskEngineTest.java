package top.aole.vend.modules.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.modules.basedata.domain.entity.OpLog;
import top.aole.vend.modules.basedata.infrastructure.mapper.OpLogMapper;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.task.domain.entity.RoutineTask;
import top.aole.vend.modules.task.domain.entity.TaskInstance;
import top.aole.vend.modules.task.domain.entity.UserRole;
import top.aole.vend.modules.task.dto.TaskDtos;
import top.aole.vend.modules.task.mapper.RoutineTaskMapper;
import top.aole.vend.modules.task.mapper.TaskInstanceMapper;
import top.aole.vend.modules.task.mapper.UserRoleMapper;
import top.aole.vend.modules.task.service.TaskService;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-6 任务日历引擎集成测试(vend_test_task 独立库):
 * 懒生成幂等 / 系统校验自动完成 / 手动补标黄标 / 逾期红灯 / 转派留痕 / 单人模式合并 / 周期判定。
 */
@ActiveProfiles("test-task")
class TaskEngineTest extends BaseIntegrationTest {

    private static final Long P = 601L;
    private static final Long M = 701L;

    @Autowired
    private TaskService taskService;
    @Autowired
    private RoutineTaskMapper routineTaskMapper;
    @Autowired
    private TaskInstanceMapper taskInstanceMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private ImportBatchMapper importBatchMapper;
    @Autowired
    private OpLogMapper opLogMapper;
    @Autowired
    private top.aole.vend.modules.stocktake.money.mapper.CashCheckMapper cashCheckMapper;
    @Autowired
    private top.aole.vend.modules.settlement.mapper.SettlementMapper settlementMapper;

    private final LocalDate today = LocalDate.now();

    /**
     * M3-9 稳定性修复:种子 replenish_patrol 的 anchor_date=迁移当日(CURRENT_DATE),
     * "每3天"仅在 (today−迁移日)%3==0 的日子到期——本类多条用例断言"今天到期",
     * 原样跑三天里挂两天。测试内把锚点校准到今天(事务回滚,不污染库)。
     */
    @org.junit.jupiter.api.BeforeEach
    void reAnchorPatrolToToday() {
        routineTaskMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RoutineTask>()
                        .eq(RoutineTask::getTaskKey, "replenish_patrol")
                        .set(RoutineTask::getAnchorDate, today));
    }

    private RoutineTask defByKey(String key) {
        return routineTaskMapper.selectOne(new LambdaQueryWrapper<RoutineTask>()
                .eq(RoutineTask::getTaskKey, key));
    }

    private TaskInstance instOf(String key, LocalDate date) {
        return taskInstanceMapper.selectOne(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskKey, key)
                .eq(TaskInstance::getTaskDate, date));
    }

    @Test
    @DisplayName("① 懒生成幂等:今日视图查两遍,实例不重复;种子任务每日+每3天(锚点日)都物化")
    void lazyGenerationIdempotent() {
        TaskDtos.TodayViewResp first = taskService.todayView(today);
        long countAfterFirst = taskInstanceMapper.selectCount(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskDate, today));
        assertTrue(countAfterFirst >= 2, "每日导数据 + 每3天补货巡检(锚点=建档日)今天都该到期");
        assertNotNull(instOf("daily_import", today));
        assertNotNull(instOf("replenish_patrol", today));

        // 再查一遍(懒生成入口再次触发)→ 数量不变=幂等
        TaskDtos.TodayViewResp second = taskService.todayView(today);
        long countAfterSecond = taskInstanceMapper.selectCount(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskDate, today));
        assertEquals(countAfterFirst, countAfterSecond, "重复查询不许重复生成实例");
        assertEquals(first.getInstances().size(), second.getInstances().size());
    }

    @Test
    @DisplayName("② 系统校验自动完成:造一条成功 import_batch → 导数据任务自动变绿(done_type=系统校验)")
    void autoCheckImportBatchTurnsGreen() {
        // 先物化:还没有导入批次 → 待办
        taskService.todayView(today);
        assertEquals(TaskInstance.STATUS_TODO, instOf("daily_import", today).getInstanceStatus());

        // 造"系统真收到文件"的证据(批次状态=已导入,create_time=DB NOW=今天)
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo("TB-" + System.nanoTime());
        batch.setFileName("test-出货明细.xlsx");
        batch.setFileType(ImportBatch.TYPE_SALE);
        batch.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        batch.setRowTotal(1);
        batch.setRowOk(1);
        importBatchMapper.insert(batch);

        taskService.todayView(today);
        TaskInstance inst = instOf("daily_import", today);
        assertEquals(TaskInstance.STATUS_DONE, inst.getInstanceStatus(), "校验通过必须自动打勾");
        assertEquals(TaskInstance.DONE_AUTO, inst.getDoneType(), "完成方式必须=系统校验✅");
        assertEquals("系统", inst.getDoneBy());
    }

    @Test
    @DisplayName("②b REPLENISH 校验:当日确认出库上架单 → 补货巡检自动完成")
    void autoCheckReplenishByTransferDoc() {
        taskService.todayView(today);
        assertEquals(TaskInstance.STATUS_TODO, instOf("replenish_patrol", today).getInstanceStatus());

        stockWarehouse(P, "100");
        confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                today, false, null, new Object[]{P, "30", "3.5"});

        taskService.todayView(today);
        TaskInstance inst = instOf("replenish_patrol", today);
        assertEquals(TaskInstance.STATUS_DONE, inst.getInstanceStatus());
        assertEquals(TaskInstance.DONE_AUTO, inst.getDoneType());
    }

    @Test
    @DisplayName("③ 手动补标:未过系统校验只能拿🟡黄标,op_log 留痕")
    void manualCompleteIsYellowWithTrace() {
        taskService.todayView(today);
        TaskInstance inst = instOf("replenish_patrol", today);
        assertEquals(TaskInstance.STATUS_TODO, inst.getInstanceStatus());

        taskService.manualComplete(inst.getId(), "今天顺路巡过了,后台没导记录", "刘俊添");

        TaskInstance done = taskInstanceMapper.selectById(inst.getId());
        assertEquals(TaskInstance.STATUS_DONE, done.getInstanceStatus());
        assertEquals(TaskInstance.DONE_MANUAL, done.getDoneType(), "手动补标≠系统校验,必须黄标");
        assertEquals("刘俊添", done.getDoneBy());
        assertNotNull(done.getDoneNote());

        List<OpLog> logs = opLogMapper.selectList(new LambdaQueryWrapper<OpLog>()
                .eq(OpLog::getTargetType, "task_instance")
                .eq(OpLog::getTargetId, inst.getId())
                .eq(OpLog::getAction, "手动补标"));
        assertEquals(1, logs.size(), "手动补标必须 op_log 留痕");
        assertEquals("刘俊添", logs.get(0).getUserName());

        // 重复补标要拦
        assertThrows(Exception.class,
                () -> taskService.manualComplete(inst.getId(), "再标一次", "刘俊添"));
    }

    @Test
    @DisplayName("④ 逾期红灯:昨日未完成实例 → 今日视图标'逾期'并进 overdue 红灯列表")
    void overdueTurnsRedNextDay() {
        RoutineTask def = defByKey("daily_import");
        TaskInstance stale = new TaskInstance();
        stale.setTaskId(def.getId());
        stale.setTaskKey(def.getTaskKey());
        stale.setTaskName(def.getTaskName());
        stale.setTaskDate(today.minusDays(1));
        stale.setAssigneeRole(def.getAssigneeRole());
        stale.setCheckType(def.getCheckType());
        stale.setInstanceStatus(TaskInstance.STATUS_TODO);
        stale.setTransferCount(0);
        taskInstanceMapper.insert(stale);

        TaskDtos.TodayViewResp view = taskService.todayView(today);

        TaskInstance marked = taskInstanceMapper.selectById(stale.getId());
        assertEquals(TaskInstance.STATUS_OVERDUE, marked.getInstanceStatus(), "昨日待办→今日必须变逾期");
        assertTrue(view.getOverdue().stream().anyMatch(i -> i.getId().equals(stale.getId())),
                "逾期实例必须出现在红灯列表");
    }

    @Test
    @DisplayName("④b 冤枉红灯修复(P1-2):昨日真有导入批次,补物化的昨日实例应绿(系统校验✅)不应红")
    void backfilledPastDayRunsOwnDayCheckBeforeOverdue() {
        // 昨日"系统真收到文件"的证据:成功导入批次,create_time 回拨到昨天
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo("TB-" + System.nanoTime());
        batch.setFileName("test-昨日出货明细.xlsx");
        batch.setFileType(ImportBatch.TYPE_SALE);
        batch.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        batch.setRowTotal(1);
        batch.setRowOk(1);
        batch.setCreateTime(java.time.LocalDateTime.now().minusDays(1));
        importBatchMapper.insert(batch);

        // 对照组:一个昨日没有任何校验证据的每日任务(REPLENISH,昨日无出库上架单)
        TaskDtos.TaskDefReq defReq = new TaskDtos.TaskDefReq();
        defReq.setTaskName("P1-2对照巡检");
        defReq.setCycleType(RoutineTask.CYCLE_DAILY);
        defReq.setCheckType(RoutineTask.CHECK_REPLENISH);
        defReq.setEnabled(true);
        taskService.createDef(defReq, "测试员");

        // 周视图补物化路径:昨日实例此刻才生成,随后 markOverdue 定罪前必须先跑"昨日"的校验
        taskService.weekView(today.minusDays(6), today);

        TaskInstance imported = instOf("daily_import", today.minusDays(1));
        assertNotNull(imported, "昨日导数据实例已补物化");
        assertEquals(TaskInstance.STATUS_DONE, imported.getInstanceStatus(),
                "昨日真导了数据 → 补物化实例必须绿灯,不许冤枉成逾期");
        assertEquals(TaskInstance.DONE_AUTO, imported.getDoneType(), "完成方式=系统校验✅(不是黄标)");
        assertEquals("系统", imported.getDoneBy());

        // 对照:昨日确实没干活的任务,照旧红灯——尺子没有放松
        TaskInstance control = taskInstanceMapper.selectOne(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskName, "P1-2对照巡检")
                .eq(TaskInstance::getTaskDate, today.minusDays(1)));
        assertNotNull(control, "对照任务昨日实例已补物化");
        assertEquals(TaskInstance.STATUS_OVERDUE, control.getInstanceStatus(),
                "昨日没证据的任务仍然必须标逾期(校验不过才定罪)");
    }

    @Test
    @DisplayName("⑤ 转派留痕:from/to/原因写进 op_log,实例责任人更新,已完成不许转")
    void transferLeavesTrace() {
        userRoleMapper.insert(role("小邱", UserRole.ROLE_CLERK));
        userRoleMapper.insert(role("陈工", UserRole.ROLE_REPLENISH));

        taskService.todayView(today);
        TaskInstance inst = instOf("daily_import", today);
        assertEquals("小邱", inst.getAssigneeUserName(), "角色内默认:CLERK 第一个在岗的人");

        TaskDtos.TransferReq req = new TaskDtos.TransferReq();
        req.setToUserName("陈工");
        req.setReason("小邱请假");
        taskService.transfer(inst.getId(), req, "刘俊添");

        TaskInstance after = taskInstanceMapper.selectById(inst.getId());
        assertEquals("陈工", after.getAssigneeUserName());
        assertEquals(1, after.getTransferCount());

        List<OpLog> logs = opLogMapper.selectList(new LambdaQueryWrapper<OpLog>()
                .eq(OpLog::getTargetType, "task_instance")
                .eq(OpLog::getTargetId, inst.getId())
                .eq(OpLog::getAction, "转派"));
        assertEquals(1, logs.size(), "转派必须 op_log 留痕");
        assertTrue(logs.get(0).getAfterJson().contains("小邱"), "留痕须含 from");
        assertTrue(logs.get(0).getAfterJson().contains("陈工"), "留痕须含 to");
        assertTrue(logs.get(0).getAfterJson().contains("小邱请假"), "留痕须含原因");

        // 完成后不许转派
        taskService.manualComplete(inst.getId(), "补", "刘俊添");
        TaskDtos.TransferReq again = new TaskDtos.TransferReq();
        again.setToUserName("小邱");
        assertThrows(Exception.class, () -> taskService.transfer(inst.getId(), again, "刘俊添"));
    }

    @Test
    @DisplayName("⑥ 单人模式:所有角色同一人→列合并;加第二个人→自动拆列")
    void singleUserModeMergesColumns() {
        // 没配人 / 全部角色映射到同一人 → 单人模式,一列 ALL
        userRoleMapper.insert(role("刘俊添", UserRole.ROLE_BOSS));
        userRoleMapper.insert(role("刘俊添", UserRole.ROLE_CLERK));
        userRoleMapper.insert(role("刘俊添", UserRole.ROLE_REPLENISH));
        TaskDtos.TodayViewResp merged = taskService.todayView(today);
        assertTrue(merged.isSingleUserMode());
        assertEquals(1, merged.getColumns().size(), "单人模式必须合并成一列");
        assertEquals("ALL", merged.getColumns().get(0).getRoleCode());

        // 加人 → 自动拆列
        userRoleMapper.insert(role("小邱", UserRole.ROLE_CLERK));
        TaskDtos.TodayViewResp split = taskService.todayView(today);
        assertFalse(split.isSingleUserMode(), "第二个人加入必须退出单人模式");
        assertTrue(split.getColumns().size() >= 2, "按角色拆列");
    }

    @Test
    @DisplayName("⑦ 周期判定:每3天按锚点取模;每月N日超月长收敛月末;每周N对齐星期")
    void cycleDueLogic() {
        RoutineTask every3 = new RoutineTask();
        every3.setCycleType(RoutineTask.CYCLE_EVERY_N_DAYS);
        every3.setCycleValue(3);
        every3.setAnchorDate(LocalDate.of(2026, 8, 1));
        assertTrue(taskService.isDue(every3, LocalDate.of(2026, 8, 1)));
        assertFalse(taskService.isDue(every3, LocalDate.of(2026, 8, 2)));
        assertTrue(taskService.isDue(every3, LocalDate.of(2026, 8, 4)));
        assertFalse(taskService.isDue(every3, LocalDate.of(2026, 7, 31)), "锚点前不到期");

        RoutineTask monthly30 = new RoutineTask();
        monthly30.setCycleType(RoutineTask.CYCLE_MONTHLY);
        monthly30.setCycleValue(30);
        assertTrue(taskService.isDue(monthly30, LocalDate.of(2026, 2, 28)), "2月没有30号→月末补上");
        assertFalse(taskService.isDue(monthly30, LocalDate.of(2026, 2, 27)));
        assertTrue(taskService.isDue(monthly30, LocalDate.of(2026, 3, 30)));

        RoutineTask weeklyMon = new RoutineTask();
        weeklyMon.setCycleType(RoutineTask.CYCLE_WEEKLY);
        weeklyMon.setCycleValue(1);
        assertTrue(taskService.isDue(weeklyMon, LocalDate.of(2026, 8, 3)), "2026-08-03 是周一");
        assertFalse(taskService.isDue(weeklyMon, LocalDate.of(2026, 8, 4)));
    }

    @Test
    @DisplayName("⑧ 本周视图:过去/今日物化,未来日只出预告不落库")
    void weekViewDoesNotMaterializeFuture() {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        List<TaskDtos.WeekDay> days = taskService.weekView(monday, today);
        assertEquals(7, days.size());
        for (TaskDtos.WeekDay d : days) {
            if (d.getDate().isAfter(today)) {
                assertTrue(d.getInstances().isEmpty(), "未来日不许物化实例");
                assertFalse(d.getPreview().isEmpty(), "未来日应有每日任务预告");
                long persisted = taskInstanceMapper.selectCount(new LambdaQueryWrapper<TaskInstance>()
                        .eq(TaskInstance::getTaskDate, d.getDate()));
                assertEquals(0L, persisted, "未来日实例不落库");
            }
        }
    }

    @Test
    @DisplayName("⑨ M3-9七律修复:结算核对/月度钱盘两条种子任务落地——每月2日/1日到期;SETTLEMENT/CASH_CHECK 系统校验真数据才变绿")
    void moneyTaskSeedsAndChecks() {
        // 种子存在且周期/校验类型正确(V1.0.14 迁移兑现 Money.vue「⚡自动任务」承诺)
        RoutineTask settle = defByKey("settlement_check");
        assertNotNull(settle, "平台结算核对种子必须存在");
        assertEquals(RoutineTask.CYCLE_MONTHLY, settle.getCycleType());
        assertEquals(2, settle.getCycleValue().intValue(), "每月 2 日");
        assertEquals(RoutineTask.CHECK_SETTLEMENT, settle.getCheckType());
        RoutineTask cash = defByKey("monthly_cash_check");
        assertNotNull(cash, "月度钱盘种子必须存在");
        assertEquals(RoutineTask.CYCLE_MONTHLY, cash.getCycleType());
        assertEquals(1, cash.getCycleValue().intValue(), "每月 1 日");
        assertEquals(RoutineTask.CHECK_CASH_CHECK, cash.getCheckType());
        // 到期判定
        assertTrue(taskService.isDue(settle, LocalDate.of(2026, 8, 2)));
        assertFalse(taskService.isDue(settle, LocalDate.of(2026, 8, 3)));
        assertTrue(taskService.isDue(cash, LocalDate.of(2026, 8, 1)));

        // —— CASH_CHECK:没有已完成钱盘 → 红;造一张已完成 → 绿 ——
        String month = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        assertFalse(taskService.runCheck(RoutineTask.CHECK_CASH_CHECK, today), "没有钱盘记录不许打勾");
        top.aole.vend.modules.stocktake.money.domain.entity.CashCheck check =
                new top.aole.vend.modules.stocktake.money.domain.entity.CashCheck();
        check.setCheckNo("QP-TEST-" + System.nanoTime() % 1_000_000);
        check.setCheckPeriod(month);
        check.setCheckStatus(top.aole.vend.modules.stocktake.money.domain.entity.CashCheck.ST_DONE);
        check.setSettleModeSnap("UNSET");
        check.setPlatformSkipped(true);
        cashCheckMapper.insert(check);
        assertTrue(taskService.runCheck(RoutineTask.CHECK_CASH_CHECK, today), "当月已完成钱盘 → 系统校验通过");

        // —— SETTLEMENT:没有已核销/已核对单 → 红;造一张已核销 → 绿 ——
        assertFalse(taskService.runCheck(RoutineTask.CHECK_SETTLEMENT, today));
        top.aole.vend.modules.settlement.domain.entity.Settlement s =
                new top.aole.vend.modules.settlement.domain.entity.Settlement();
        s.setStmtNo("PT-TEST-" + System.nanoTime() % 1_000_000);
        s.setPeriodStart(today.withDayOfMonth(1));
        s.setPeriodEnd(today);
        s.setPlatformAmount(java.math.BigDecimal.TEN);
        s.setStlStatus(top.aole.vend.modules.settlement.domain.entity.Settlement.ST_SETTLED);
        s.setModeSnap("PLATFORM");
        s.setConfirmAt(java.time.LocalDateTime.now());
        settlementMapper.insert(s);
        assertTrue(taskService.runCheck(RoutineTask.CHECK_SETTLEMENT, today), "当月已核销结算单 → 系统校验通过");

        // —— autoCheckByType(钱盘收口主动触发的同一入口):月初实例被自动打勾 ✅ ——
        LocalDate firstDay = today.withDayOfMonth(1);
        taskService.materializeDay(firstDay);
        TaskInstance inst = instOf("monthly_cash_check", firstDay);
        assertNotNull(inst, "每月 1 日物化钱盘任务实例");
        int passed = taskService.autoCheckByType(RoutineTask.CHECK_CASH_CHECK);
        assertTrue(passed >= 1, "收口触发自动校验至少打勾 1 条");
        TaskInstance done = instOf("monthly_cash_check", firstDay);
        assertEquals(TaskInstance.STATUS_DONE, done.getInstanceStatus());
        assertEquals(TaskInstance.DONE_AUTO, done.getDoneType(), "系统校验 ✅,不是手动补标 🟡");
        assertEquals("系统", done.getDoneBy());
    }

    private UserRole role(String name, String code) {
        UserRole ur = new UserRole();
        // 唯一键含 user_id:SSO 前按人名生成稳定伪 ID(与 UserRoleController 同规则)
        ur.setUserId((long) Math.abs(name.hashCode()));
        ur.setUserName(name);
        ur.setRoleCode(code);
        return ur;
    }
}
