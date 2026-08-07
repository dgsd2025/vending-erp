package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.infrastructure.mapper.SlotMapper;
import top.aole.vend.modules.replenish.domain.entity.ReplenishPlan;
import top.aole.vend.modules.replenish.mapper.ReplenishPlanMapper;
import top.aole.vend.modules.replenish.service.ReplenishEngine;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 场景17:慢销与容量(补货引擎两条边界铁律)。
 *
 * ① 慢销品(日均<1.5)不套 (R,S) 公式:低于 min 箱直接补到 max 箱(safety_stock 必须为 NULL);
 *    高于 min 水位则一行建议都不出(不给慢销品瞎压货);
 * ② 机器侧建议被货道容量硬约束截断:日均再大,par level 目标水位=货道容量,
 *    建议量 = 容量 − 机内现有,绝不超过货道装得下的数(跑一趟的人力比压几瓶货贵)。
 */
class Scenario17SlowMoverCapacityTest extends RegressionSupport {

    @Autowired
    private ReplenishEngine replenishEngine;
    @Autowired
    private ReplenishPlanMapper planMapper;
    @Autowired
    private SlotMapper slotMapper;

    @Test
    @DisplayName("慢销min-max:日均1(<1.5)不套公式——低于min补到max 1箱=12,SS=NULL;库存高于min的慢销品不出建议")
    void slowMoverUsesMinMaxNotRsFormula() {
        Machine m = machine("慢销测试机");
        // 慢销A:仓库 0 → 低于 min(0.5箱=6)→ 补到 max(1箱=12)
        Product slowA = product("慢销卤鸭脖", null, null);
        setBoxSpec(slowA, "12");
        dailySales(m.getId(), slowA.getId(), 7, "1"); // 日均 1 < 1.5
        // 慢销B:仓库 6 = min 水位 → 不缺,不出建议
        Product slowB = product("慢销豆干", null, null);
        setBoxSpec(slowB, "12");
        dailySales(m.getId(), slowB.getId(), 7, "1");
        stockWarehouse(slowB.getId(), "6");

        replenishEngine.recalc(OPERATOR);

        ReplenishPlan planA = purchasePlanOf(slowA.getId());
        assertNotNull(planA, "慢销A 低于 min 必出采购建议");
        assertNull(planA.getSafetyStock(), "慢销不套公式:SS 必须 NULL(不是 0,是压根不算)");
        assertEquals(0, planA.getTargetLevelS().compareTo(new BigDecimal("12")),
                "补到水位 = max 1 箱 × 箱规 12");
        assertEquals(0, planA.getSuggestQty().compareTo(new BigDecimal("12")),
                "建议量 = max 水位 − 可用 0 = 12");
        assertEquals(0, planA.getBoxRoundQty().compareTo(new BigDecimal("12")), "整箱出数");
        assertTrue(planA.getFormulaJson().contains("慢销"), "过程快照标明慢销策略:" + planA.getFormulaJson());
        assertTrue(planA.getFormulaJson().contains("慢销min(箱)"), "min/max 参数进快照可解释");

        assertNull(purchasePlanOf(slowB.getId()),
                "慢销B 可用 6 = min 水位,不低于 min → 不出建议(不给慢销品压货)");
    }

    @Test
    @DisplayName("容量硬约束:日均20的畅销品,货道只装10 → 目标水位=容量10,建议=10−机内2=8,绝不按需求量放大")
    void machineSuggestionHardCappedBySlotCapacity() {
        Machine m = machine("小货道机");
        Product hot = product("畅销冰红茶", null, null);
        slot(m.getId(), hot.getId(), "A1", "10"); // 货道只装 10
        dailySales(m.getId(), hot.getId(), 7, "20"); // 日均 20,理论需求远超容量
        // 机内现有 2(锚点晚于全部销售)
        stockService.recordMachineSnapshot(m.getId(), hot.getId(), "A1", new BigDecimal("2"),
                MachineStockSnapshot.SRC_BACKEND_PAGE, LocalDateTime.now(), OP);

        replenishEngine.recalc(OPERATOR);

        ReplenishPlan plan = planMapper.selectOne(new LambdaQueryWrapper<ReplenishPlan>()
                .eq(ReplenishPlan::getPlanType, ReplenishEngine.TYPE_MACHINE)
                .eq(ReplenishPlan::getMachineId, m.getId())
                .eq(ReplenishPlan::getProductId, hot.getId()));
        assertNotNull(plan, "机器侧建议在");
        assertEquals(0, plan.getTargetLevelS().compareTo(new BigDecimal("10")),
                "目标水位被货道容量硬截断=10(不是日均×天数)");
        assertEquals(0, plan.getSuggestQty().compareTo(new BigDecimal("8")),
                "建议 = 容量 10 − 机内 2 = 8");
        assertTrue(plan.getSuggestQty().compareTo(new BigDecimal("10")) <= 0,
                "建议量永远 ≤ 货道容量(硬约束)");
        assertTrue(plan.getFormulaJson().contains("货道容量"),
                "过程快照写明容量口径:" + plan.getFormulaJson());
        assertEquals(0, plan.getCurrentQty().compareTo(new BigDecimal("2")), "机内现有取锚点推算 2");
    }

    // ============================== 造数辅助 ==============================

    private void setBoxSpec(Product p, String boxSpec) {
        p.setBoxSpec(new BigDecimal(boxSpec));
        productMapper.updateById(p);
    }

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

    private void dailySales(Long machineId, Long productId, int days, String qty) {
        LocalDate end = LocalDate.now().minusDays(1);
        for (int i = 0; i < days; i++) {
            insertSale(machineId, productId, qty, "正常",
                    LocalDateTime.of(end.minusDays(i), LocalTime.NOON));
        }
    }

    private ReplenishPlan purchasePlanOf(Long productId) {
        List<ReplenishPlan> list = planMapper.selectList(new LambdaQueryWrapper<ReplenishPlan>()
                .eq(ReplenishPlan::getPlanType, ReplenishEngine.TYPE_PURCHASE)
                .eq(ReplenishPlan::getProductId, productId));
        return list.isEmpty() ? null : list.get(0);
    }
}
