package top.aole.vend.modules.replenish;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SlotMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.replenish.domain.entity.ReplenishPlan;
import top.aole.vend.modules.replenish.mapper.ReplenishPlanMapper;
import top.aole.vend.modules.replenish.service.DemandStatsService;
import top.aole.vend.modules.replenish.service.ReplenishEngine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-1/M2-2 集成测试(vend_test_replenish 库):
 * 真数据链路 = sale_record 日聚合 → 需求统计 → (R,S)/par level 引擎 → replenish_plan 落表
 * + AI 解释(mock)四件套 + 幂等重算 + 已忽略保留 + 清仓中排除。
 */
@ActiveProfiles("test-replenish")
class ReplenishEngineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReplenishEngine engine;
    @Autowired
    private DemandStatsService demandStatsService;
    @Autowired
    private ReplenishPlanMapper planMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private MachineMapper machineMapper;
    @Autowired
    private SlotMapper slotMapper;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private PurchaseOrderService poService;

    // ============================== 造数 ==============================

    private Product product(String name, String boxSpec, Integer shelfLife, String status) {
        Product p = new Product();
        p.setSkuCode("TR-" + IdUtil.getSnowflakeNextIdStr().substring(9));
        p.setProductName(name);
        p.setUnit("瓶");
        p.setBoxSpec(new BigDecimal(boxSpec));
        p.setShelfLifeDays(shelfLife);
        p.setProductStatus(status);
        productMapper.insert(p);
        return p;
    }

    private Machine machine(String name) {
        Machine m = new Machine();
        m.setMachineCode("TRM-" + IdUtil.getSnowflakeNextIdStr().substring(9));
        m.setMachineName(name);
        m.setDeviceId("DEV-" + IdUtil.getSnowflakeNextIdStr());
        machineMapper.insert(m);
        return m;
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

    /** 连续 days 天,每天 qty 件(截至昨天),固定 12:00 出货 */
    private void dailySales(Long machineId, Long productId, int days, String qty) {
        LocalDate end = LocalDate.now().minusDays(1);
        for (int i = 0; i < days; i++) {
            insertSale(machineId, productId, qty, "正常",
                    LocalDateTime.of(end.minusDays(i), LocalTime.NOON));
        }
    }

    // ============================== 用例 ==============================

    /** 机器侧 par level:容量 56,盘点快照 4 → 建议补 52;紧急度/售罄日/formula_json 都有 */
    @Test
    void machineSideParLevel() {
        Machine m = machine("1楼测试机");
        Product p = product("东鹏特饮测试", "24", null, "在售");
        slot(m.getId(), p.getId(), "A1", "28");
        slot(m.getId(), p.getId(), "A2", "28");
        dailySales(m.getId(), p.getId(), 28, "3"); // 该机日均 3
        // 机内现有:快照锚点 4(时间设在最后一笔出货之后,不再被销售扣减)
        stockService.recordMachineSnapshot(m.getId(), p.getId(), null, new BigDecimal("4"),
                "盘点", LocalDateTime.now(), OP);

        engine.recalc("测试员");

        ReplenishPlan plan = onePlan(ReplenishEngine.TYPE_MACHINE, m.getId(), p.getId());
        assertThat(plan.getTargetLevelS()).isEqualByComparingTo("56");
        assertThat(plan.getCurrentQty()).isEqualByComparingTo("4");
        assertThat(plan.getSuggestQty()).isEqualByComparingTo("52");
        assertThat(plan.getAvgDaily()).isEqualByComparingTo("3");
        assertThat(plan.getFormulaJson()).contains("货道容量合计").contains("紧急度").contains("预计售罄日");
        assertThat(plan.getAiExplain()).isNotBlank();
        assertThat(plan.getLlmCallId()).isNotNull();
    }

    /** 仓库侧 (R,S) 真链路:28 天日销 20(σ=0,星期系数全 1)→ S=320;仓 80 + 机内 0 + 在途 48 → Q=192 → 8 箱 */
    @Test
    void purchaseSideRsFormulaWithInTransit() {
        Product p = product("矿泉水测试", "24", null, "在售");
        Machine m = machine("2楼测试机");
        dailySales(m.getId(), p.getId(), 28, "20");
        stockWarehouse(p.getId(), "80"); // 仓库垫底 80
        // 机内推算会是负数(有销售无补货)→ 引擎按 0 参与
        inTransit(p.getId(), "48");

        engine.recalc("测试员");

        ReplenishPlan plan = onePlan(ReplenishEngine.TYPE_PURCHASE, null, p.getId());
        // σ=0 → SS=0;星期系数全 1 → 周期需求 = 20×16 = 320
        assertThat(plan.getTargetLevelS()).isEqualByComparingTo("320");
        assertThat(plan.getSafetyStock()).isEqualByComparingTo("0");
        assertThat(plan.getCurrentQty()).isEqualByComparingTo("128"); // 80 + 0 + 48(在途扣减)
        assertThat(plan.getSuggestQty()).isEqualByComparingTo("192");
        assertThat(plan.getBoxRoundQty()).isEqualByComparingTo("192"); // 恰好 8 整箱
        assertThat(plan.getFormulaJson()).contains("在途").contains("星期系数启用");
    }

    /** 慢销品:日均 1(<1.5)→ min/max 直配,不套公式 */
    @Test
    void purchaseSideSlowMover() {
        Product p = product("慢销卤味测试", "12", null, "在售");
        Machine m = machine("3楼测试机");
        dailySales(m.getId(), p.getId(), 28, "1"); // 日均 1
        // 仓库 0、机内负(按0)、在途 0 → 低于 min 0.5 箱 → 补到 max 1 箱 = 12

        engine.recalc("测试员");

        ReplenishPlan plan = onePlan(ReplenishEngine.TYPE_PURCHASE, null, p.getId());
        assertThat(plan.getSafetyStock()).isNull(); // 不套公式
        assertThat(plan.getTargetLevelS()).isEqualByComparingTo("12");
        assertThat(plan.getSuggestQty()).isEqualByComparingTo("12");
        assertThat(plan.getFormulaJson()).contains("慢销");
    }

    /** 清仓中商品:两侧都不出建议 */
    @Test
    void clearanceExcluded() {
        Machine m = machine("4楼测试机");
        Product p = product("清仓饼干测试", "24", null, "清仓中");
        slot(m.getId(), p.getId(), "B1", "20");
        dailySales(m.getId(), p.getId(), 28, "5");

        engine.recalc("测试员");

        assertThat(plans(p.getId())).isEmpty();
    }

    /** 幂等重算:同日跑两次不翻倍;已忽略的行保留、不被覆盖也不重复生成 */
    @Test
    void recalcIdempotentAndIgnoredPreserved() {
        Machine m = machine("5楼测试机");
        Product p = product("幂等测试水", "24", null, "在售");
        slot(m.getId(), p.getId(), "C1", "30");
        dailySales(m.getId(), p.getId(), 28, "4");

        engine.recalc("测试员");
        List<ReplenishPlan> first = plans(p.getId());
        assertThat(first).hasSize(2); // 机器侧 + 仓库侧各 1

        engine.recalc("测试员");
        assertThat(plans(p.getId())).hasSize(2); // 不翻倍

        // 忽略机器侧那条 → 再重算:该行保留「已忽略」,同键不重复生成
        ReplenishPlan machineRow = onePlan(ReplenishEngine.TYPE_MACHINE, m.getId(), p.getId());
        engine.setIgnored(machineRow.getId(), true, "测试员");
        engine.recalc("测试员");

        List<ReplenishPlan> after = plans(p.getId());
        assertThat(after).hasSize(2);
        ReplenishPlan ignored = onePlan(ReplenishEngine.TYPE_MACHINE, m.getId(), p.getId());
        assertThat(ignored.getId()).isEqualTo(machineRow.getId());
        assertThat(ignored.getPlanStatus()).isEqualTo("已忽略");
    }

    /** 🔬 过程详情:公式快照 + llm 四件套(推理/输出/置信/原始数据)全在 */
    @Test
    void planDetailTransparency() {
        Machine m = machine("6楼测试机");
        Product p = product("透明度测试茶", "15", null, "在售");
        slot(m.getId(), p.getId(), "D1", "25");
        dailySales(m.getId(), p.getId(), 28, "2");

        engine.recalc("测试员");
        ReplenishPlan plan = onePlan(ReplenishEngine.TYPE_MACHINE, m.getId(), p.getId());

        Map<String, Object> detail = engine.planDetail(plan.getId());
        assertThat(detail).containsKey("plan").containsKey("llm");
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) detail.get("llm");
        assertThat(llm.get("reasoning")).isNotNull();   // 推理过程
        assertThat(llm.get("outputText")).isNotNull();  // 完整输出
        assertThat(llm.get("confidence")).isNotNull();  // 置信分(=样本天数/28)
        assertThat(llm.get("inputDigest")).isNotNull(); // 原始数据
        assertThat(new BigDecimal(llm.get("confidence").toString())).isEqualByComparingTo("1");
    }

    /**
     * 盲审 P1-3:窗口内 0 销量的在售 SKU 也要进采购建议——慢销 min/max 本来就是为
     * "卖得最慢的"设计的,28 天颗粒无收恰恰是它最该兜住的对象(修复前从建议里整个消失)。
     * 清仓中/停售仍然排除。
     */
    @Test
    void zeroSalesOnSaleSkuStillGetsSlowMoverSuggestion() {
        Machine m = machine("8楼测试机");
        Product anchor = product("统计锚点水", "24", null, "在售"); // 有销量,撑起 dataAsOf
        dailySales(m.getId(), anchor.getId(), 28, "2");
        Product zero = product("零销量卤蛋", "12", null, "在售");       // 28 天颗粒无收,仓库 0
        Product zeroClearing = product("零销量清仓饼", "12", null, "清仓中");
        Product zeroOff = product("零销量停售糖", "12", null, "停售");

        engine.recalc("测试员");

        // 零销量在售品:avg=0 → 慢销分支,可用 0 < min 0.5箱(6)→ 补到 max 1箱 = 12
        ReplenishPlan plan = onePlan(ReplenishEngine.TYPE_PURCHASE, null, zero.getId());
        assertThat(plan.getAvgDaily()).isEqualByComparingTo("0");
        assertThat(plan.getSafetyStock()).isNull(); // 慢销不套公式
        assertThat(plan.getTargetLevelS()).isEqualByComparingTo("12");
        assertThat(plan.getSuggestQty()).isEqualByComparingTo("12");
        assertThat(plan.getBoxRoundQty()).isEqualByComparingTo("12");
        assertThat(plan.getFormulaJson()).contains("慢销");
        // 清仓中/停售:照旧一行不出
        assertThat(plans(zeroClearing.getId())).isEmpty();
        assertThat(plans(zeroOff.getId())).isEmpty();
    }

    /** 需求统计快照:数据截至日 = 最后一笔出货日;样本不足 SKU 星期系数禁用 */
    @Test
    void demandSnapshotBasics() {
        Machine m = machine("7楼测试机");
        Product full = product("满样本测试", "24", null, "在售");
        Product young = product("新品测试", "24", null, "在售");
        dailySales(m.getId(), full.getId(), 28, "6");
        dailySales(m.getId(), young.getId(), 7, "6"); // 只有 7 天

        DemandStatsService.Snapshot snap = demandStatsService.compute();
        assertThat(snap.asOf).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(snap.global.get(full.getId()).isWeekdayEnabled()).isTrue();
        assertThat(snap.global.get(full.getId()).getAvgDaily()).isEqualByComparingTo("6");
        assertThat(snap.global.get(young.getId()).isWeekdayEnabled()).isFalse();
        assertThat(snap.global.get(young.getId()).getSampleDays()).isEqualTo(7);
    }

    // ============================== 辅助 ==============================

    /** 造在途:建订货单并下单(在途 = Σ未收) */
    private void inTransit(Long productId, String qty) {
        Supplier s = new Supplier();
        s.setSupplierCode("TRS-" + IdUtil.getSnowflakeNextIdStr().substring(9));
        s.setSupplierName("测试供应商");
        supplierMapper.insert(s);
        PoCreateReq req = new PoCreateReq();
        req.setSupplierId(s.getId());
        PoCreateReq.Item item = new PoCreateReq.Item();
        item.setProductId(productId);
        item.setQtyOrdered(new BigDecimal(qty));
        item.setUnitPrice(BigDecimal.ONE);
        req.setItems(Collections.singletonList(item));
        Long poId = poService.create(req, "测试员");
        poService.place(poId, "测试员");
    }

    private List<ReplenishPlan> plans(Long productId) {
        return planMapper.selectList(new LambdaQueryWrapper<ReplenishPlan>()
                .eq(ReplenishPlan::getProductId, productId));
    }

    private ReplenishPlan onePlan(String type, Long machineId, Long productId) {
        LambdaQueryWrapper<ReplenishPlan> qw = new LambdaQueryWrapper<ReplenishPlan>()
                .eq(ReplenishPlan::getPlanType, type)
                .eq(ReplenishPlan::getProductId, productId);
        if (machineId == null) {
            qw.isNull(ReplenishPlan::getMachineId);
        } else {
            qw.eq(ReplenishPlan::getMachineId, machineId);
        }
        List<ReplenishPlan> list = planMapper.selectList(qw);
        assertThat(list).hasSize(1);
        return list.get(0);
    }
}
