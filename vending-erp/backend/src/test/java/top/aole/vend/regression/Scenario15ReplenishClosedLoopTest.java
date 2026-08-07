package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.infrastructure.mapper.SlotMapper;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.prekit.domain.entity.PrekitTicket;
import top.aole.vend.modules.prekit.domain.entity.PrekitTicketItem;
import top.aole.vend.modules.prekit.dto.PrekitDtos;
import top.aole.vend.modules.prekit.mapper.PrekitTicketItemMapper;
import top.aole.vend.modules.prekit.mapper.PrekitTicketMapper;
import top.aole.vend.modules.prekit.service.PrekitService;
import top.aole.vend.modules.replenish.domain.entity.ReplenishPlan;
import top.aole.vend.modules.replenish.mapper.ReplenishPlanMapper;
import top.aole.vend.modules.replenish.service.ReplenishEngine;
import top.aole.vend.modules.task.domain.entity.RoutineTask;
import top.aole.vend.modules.task.domain.entity.TaskInstance;
import top.aole.vend.modules.task.mapper.RoutineTaskMapper;
import top.aole.vend.modules.task.mapper.TaskInstanceMapper;
import top.aole.vend.modules.task.service.TaskService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 场景15:补货全闭环(跨模块端到端,M2 最有价值主链)。
 *
 * 链路:销售数据(通道1真导入)→ (R,S)/par level 引擎重算建议 → 机器侧建议生成配货单
 *      → 仓库装箱执行 → 次日通道2导入补货记录自动核销 → 带回率落表
 *      → 任务日历「补货巡检」被系统校验自动打勾(铁律#8:导数据=真凭据,不是打勾就算)。
 *
 * 覆盖模块:imports → replenish → prekit → doc/stock → task,一条线全真服务链,无 mock。
 */
class Scenario15ReplenishClosedLoopTest extends RegressionSupport {

    @Autowired
    private ReplenishEngine replenishEngine;
    @Autowired
    private ReplenishPlanMapper planMapper;
    @Autowired
    private SlotMapper slotMapper;
    @Autowired
    private PrekitService prekitService;
    @Autowired
    private PrekitTicketMapper ticketMapper;
    @Autowired
    private PrekitTicketItemMapper ticketItemMapper;
    @Autowired
    private TaskService taskService;
    @Autowired
    private RoutineTaskMapper routineTaskMapper;
    @Autowired
    private TaskInstanceMapper taskInstanceMapper;

    @Test
    @DisplayName("补货全闭环:导销售→重算建议(par level 30)→配货单→执行→导入核销(上架25/带回5)→带回率0.1667→补货巡检自动打勾")
    void fullReplenishLoopFromSalesToTaskAutoCheck() throws Exception {
        LocalDate today = LocalDate.now();
        Machine m = machine("闭环补货机");
        Product p = product("闭环东鹏特饮", "RG6915001", null);
        alias("RG6915001", "东鹏特饮闭环装", p.getId());
        slot(m.getId(), p.getId(), "A1", "30");
        stockWarehouse(p.getId(), "100");

        // ① 销售数据:近 6 天日均 4 打底 + 今天通道1真导入 5 件(数据截至日=今天)
        for (int i = 1; i <= 6; i++) {
            insertSale(m.getId(), p.getId(), "4", "正常",
                    LocalDateTime.of(today.minusDays(i), LocalTime.NOON));
        }
        ImportDtos.CommitResp saleResp = importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"BH001", "东鹏特饮闭环装", "RG6915001", 2, 1, deviceOf(m), 12.0, "正常订单", "微信", today + " 10:00:00"},
                {"BH002", "东鹏特饮闭环装", "RG6915001", 3, 1, deviceOf(m), 18.0, "正常订单", "微信", today + " 10:30:00"},
        }));
        assertEquals(2, saleResp.getRowOk(), "通道1两行销售入账");

        // ② 重算建议:机器侧 par level——机内推算为负按 0,建议补满货道 30
        Map<String, Object> summary = replenishEngine.recalc(OPERATOR);
        assertEquals(today.toString(), String.valueOf(summary.get("planDate")));
        assertEquals(today.toString(), String.valueOf(summary.get("dataAsOf")), "数据截至日=今天(刚导了今天的销售)");
        ReplenishPlan plan = planMapper.selectOne(new LambdaQueryWrapper<ReplenishPlan>()
                .eq(ReplenishPlan::getPlanType, ReplenishEngine.TYPE_MACHINE)
                .eq(ReplenishPlan::getMachineId, m.getId())
                .eq(ReplenishPlan::getProductId, p.getId()));
        assertNotNull(plan, "机器侧建议行生成");
        assertEquals(ReplenishEngine.STATUS_PENDING, plan.getPlanStatus());
        assertEquals(0, plan.getTargetLevelS().compareTo(new BigDecimal("30")), "目标水位=货道容量 30");
        assertEquals(0, plan.getSuggestQty().compareTo(new BigDecimal("30")),
                "机内推算为负(只有销售没有补货)按 0 → 建议补满 30");
        assertTrue(plan.getAvgDaily().signum() > 0, "日均销量出自真销售数据");
        assertNotNull(plan.getAiExplain(), "AI 人话解释挂在建议上");

        // ③ 勾选建议生成配货单(建议 → 已生成配货单)
        PrekitDtos.GenerateReq genReq = new PrekitDtos.GenerateReq();
        PrekitDtos.GenerateItem gi = new PrekitDtos.GenerateItem();
        gi.setPlanId(plan.getId());
        genReq.setItems(Collections.singletonList(gi));
        PrekitDtos.GenerateResp genResp = prekitService.generate(genReq, OPERATOR);
        assertEquals(1, genResp.getTicketCount());
        Long ticketId = genResp.getTicketIds().get(0);
        assertEquals("已生成配货单", planMapper.selectById(plan.getId()).getPlanStatus(), "建议行状态联动");

        // ④ 仓库装箱出发(已生成 → 已执行;建议行 → 已执行)
        prekitService.execute(ticketId, OPERATOR);
        assertEquals(PrekitTicket.STATUS_EXECUTED, ticketMapper.selectById(ticketId).getTicketStatus());
        assertEquals("已执行", planMapper.selectById(plan.getId()).getPlanStatus());

        // ⑤ 通道2导入补货记录:实上架 25(带出 30,带回 5)→ afterImport 钩子自动核销
        ImportDtos.CommitResp repResp = importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {deviceOf(m), 1, "东鹏特饮闭环装", "RG6915001", 0, 25, 25, "小邱", today + " 16:30:00"},
        }));
        assertEquals(1, repResp.getDocsCreated(), "导入生成一张转移单(唯一生产者)");
        assertEquals(1, repResp.getPrekitVerified(), "配货单被自动核销");

        // ⑥ 带回率落表:5/30 = 0.1667,明细回填实上架/带回
        PrekitTicket ticket = ticketMapper.selectById(ticketId);
        assertEquals(PrekitTicket.STATUS_DIFF, ticket.getTicketStatus(), "有带回 → 有差异");
        assertEquals(0, ticket.getTakebackRate().compareTo(new BigDecimal("0.1667")),
                "带回率=Σ带回/Σ带出=5/30");
        assertNotNull(ticket.getVerifyDocId(), "核销挂接导入转移单");
        List<PrekitTicketItem> items = ticketItemMapper.selectList(
                new LambdaQueryWrapper<PrekitTicketItem>().eq(PrekitTicketItem::getTicketId, ticketId));
        assertEquals(1, items.size());
        assertEquals(0, items.get(0).getQtyPlanned().compareTo(new BigDecimal("30")));
        assertEquals(0, items.get(0).getQtyLoaded().compareTo(new BigDecimal("25")));
        assertEquals(0, items.get(0).getQtyTakeback().compareTo(new BigDecimal("5")));

        // 三本账对得上:仓库 100−25=75;机器=补货后快照 25
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("75")));
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId()).compareTo(new BigDecimal("25")),
                "机器账以导入快照(补货后库存25)为锚");

        // ⑦ 任务日历:「补货巡检」当日有已确认出库上架单 → 系统校验自动打勾(绿✅非黄标)
        RoutineTask patrol = routineTaskMapper.selectOne(new LambdaQueryWrapper<RoutineTask>()
                .eq(RoutineTask::getTaskKey, "replenish_patrol"));
        assertNotNull(patrol, "种子任务「补货巡检」在");
        patrol.setAnchorDate(today); // 每3天锚点对齐到今天,保证今天到期(事务内改,回滚不留痕)
        routineTaskMapper.updateById(patrol);
        taskService.todayView(today);

        TaskInstance patrolInst = taskInstanceMapper.selectOne(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskKey, "replenish_patrol")
                .eq(TaskInstance::getTaskDate, today));
        assertNotNull(patrolInst, "补货巡检当日实例已物化");
        assertEquals(TaskInstance.STATUS_DONE, patrolInst.getInstanceStatus(), "系统校验自动打勾");
        assertEquals(TaskInstance.DONE_AUTO, patrolInst.getDoneType(), "完成方式=系统校验(绿✅,非手动黄标)");
        assertEquals("系统", patrolInst.getDoneBy());

        // 顺手验证「数据迁移导入」也因今天两次真导入自动变绿
        TaskInstance importInst = taskInstanceMapper.selectOne(new LambdaQueryWrapper<TaskInstance>()
                .eq(TaskInstance::getTaskKey, "daily_import")
                .eq(TaskInstance::getTaskDate, today));
        assertNotNull(importInst);
        assertEquals(TaskInstance.STATUS_DONE, importInst.getInstanceStatus());
        assertEquals(TaskInstance.DONE_AUTO, importInst.getDoneType());
    }

    // ============================== 造数辅助 ==============================

    private void slot(Long machineId, Long productId, String slotNo, String capacity) {
        Slot s = new Slot();
        s.setMachineId(machineId);
        s.setProductId(productId);
        s.setSlotNo(slotNo);
        s.setCapacity(new BigDecimal(capacity));
        s.setCurrentQty(BigDecimal.ZERO);
        s.setSlotStatus("正常");
        slotMapper.insert(s);
    }

    private String deviceOf(Machine m) {
        return machineMapper.selectById(m.getId()).getDeviceId();
    }
}
