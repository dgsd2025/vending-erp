package top.aole.vend.modules.replenish;

import org.junit.jupiter.api.Test;
import top.aole.vend.modules.replenish.domain.DemandStats;
import top.aole.vend.modules.replenish.domain.ReplenishCalc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (R,S) 纯计算器单测(无 Spring/无 DB):调研 §4.1 矿泉水算例 = 基准,数字逐项断言。
 */
class ReplenishCalcTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    private DemandStats stats(String avg, String sigma, int sampleDays) {
        DemandStats s = new DemandStats();
        s.setAvgDaily(new BigDecimal(avg));
        s.setSigmaDaily(new BigDecimal(sigma));
        s.setSampleDays(sampleDays);
        return s;
    }

    // ============================== 1. 矿泉水算例(基准) ==============================

    /** d̄=20,σ=6,R=15,L=1,Z=1.65 → 周期需求320 / SS≈40 / S=360 / 现80 → Q=280 → 整箱24 → 288 */
    @Test
    void mineralWaterBenchmark() {
        ReplenishCalc.PurchaseCalc c = ReplenishCalc.purchaseSuggest(
                stats("20", "6", 28), 15, BigDecimal.ONE, new BigDecimal("0.95"),
                new BigDecimal("80"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("24"), new BigDecimal("0.5"), BigDecimal.ONE, AS_OF);

        assertThat(c.getMode()).isEqualTo("RS公式");
        assertThat(c.getZ()).isEqualByComparingTo("1.65");
        assertThat(c.getCycleDemand()).isEqualByComparingTo("320");          // 20×(15+1)
        assertThat(c.getSafetyStock()).isEqualByComparingTo("39.6");         // 1.65×6×√16 ≈ 40
        assertThat(c.getTargetS()).isEqualByComparingTo("360");              // ceil(359.6)
        assertThat(c.getAvailable()).isEqualByComparingTo("80");
        assertThat(c.getSuggestQty()).isEqualByComparingTo("280");           // 360−80
        assertThat(c.getBoxes()).isEqualByComparingTo("12");                 // ceil(280/24)
        assertThat(c.getBoxRoundQty()).isEqualByComparingTo("288");          // 12×24
    }

    // ============================== 2. Z 查表 ==============================

    @Test
    void zTable() {
        assertThat(ReplenishCalc.zFor(new BigDecimal("0.90"))).isEqualByComparingTo("1.28");
        assertThat(ReplenishCalc.zFor(new BigDecimal("0.95"))).isEqualByComparingTo("1.65");
        assertThat(ReplenishCalc.zFor(new BigDecimal("0.98"))).isEqualByComparingTo("2.05");
        assertThat(ReplenishCalc.zFor(new BigDecimal("0.85"))).isEqualByComparingTo("1.04");
        assertThat(ReplenishCalc.zFor(null)).isEqualByComparingTo("1.65"); // 默认 95%
    }

    // ============================== 3. 在途扣减 ==============================

    /** 同矿泉水算例,但在途 48:可用 = 80+48=128 → Q = 360−128 = 232 → 10 箱 240 */
    @Test
    void inTransitDeduction() {
        ReplenishCalc.PurchaseCalc c = ReplenishCalc.purchaseSuggest(
                stats("20", "6", 28), 15, BigDecimal.ONE, new BigDecimal("0.95"),
                new BigDecimal("80"), BigDecimal.ZERO, new BigDecimal("48"),
                new BigDecimal("24"), new BigDecimal("0.5"), BigDecimal.ONE, AS_OF);
        assertThat(c.getAvailable()).isEqualByComparingTo("128");
        assertThat(c.getSuggestQty()).isEqualByComparingTo("232");
        assertThat(c.getBoxRoundQty()).isEqualByComparingTo("240");
    }

    /** 机内账为负(待盘点)按 0 参与:不虚增采购量 */
    @Test
    void negativeMachineStockClampedToZero() {
        ReplenishCalc.PurchaseCalc c = ReplenishCalc.purchaseSuggest(
                stats("20", "6", 28), 15, BigDecimal.ONE, new BigDecimal("0.95"),
                new BigDecimal("80"), new BigDecimal("-999"), BigDecimal.ZERO,
                new BigDecimal("24"), new BigDecimal("0.5"), BigDecimal.ONE, AS_OF);
        assertThat(c.getAvailable()).isEqualByComparingTo("80"); // 负机内不参与
        assertThat(c.getSuggestQty()).isEqualByComparingTo("280");
    }

    // ============================== 4. 慢销品 min/max ==============================

    /** 日均 1.0 < 1.5:不套公式;可用 10 < min(0.5箱=12) → 补到 max(1箱=24) → Q=14 → 整箱 24 */
    @Test
    void slowMoverMinMax() {
        ReplenishCalc.PurchaseCalc c = ReplenishCalc.purchaseSuggest(
                stats("1.0", "0.8", 28), 15, BigDecimal.ONE, new BigDecimal("0.95"),
                new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("24"), new BigDecimal("0.5"), BigDecimal.ONE, AS_OF);
        assertThat(c.getMode()).isEqualTo("慢销min-max");
        assertThat(c.getSafetyStock()).isNull();               // 不套公式
        assertThat(c.getTargetS()).isEqualByComparingTo("24"); // max 1 箱
        assertThat(c.getSuggestQty()).isEqualByComparingTo("14");
        assertThat(c.getBoxRoundQty()).isEqualByComparingTo("24");
    }

    /** 慢销品可用量 ≥ min 水位:不补 */
    @Test
    void slowMoverAboveMinNoSuggest() {
        ReplenishCalc.PurchaseCalc c = ReplenishCalc.purchaseSuggest(
                stats("1.0", "0.8", 28), 15, BigDecimal.ONE, new BigDecimal("0.95"),
                new BigDecimal("13"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("24"), new BigDecimal("0.5"), BigDecimal.ONE, AS_OF);
        assertThat(c.getSuggestQty()).isEqualByComparingTo("0");
    }

    // ============================== 5. 星期系数版周期需求 ==============================

    /**
     * 星期系数启用:周期需求 = 未来 R+L 天逐日预测之和(周末≈0 的园区场景被吃掉)。
     * 构造:工作日系数 1.4、周末 0,d̄=10 → R+L=7 天(asOf=周三,未来7天含周四五+周末2天+周一二三)
     * = 10×1.4×5 = 70(而均值版 = 10×7 = 70;换 R+L=2:周四五 → 10×1.4×2=28 vs 均值 20)。
     */
    @Test
    void weekdayCoefCycleDemand() {
        DemandStats s = stats("10", "0", 28);
        // 周一..周五 = 1.4,周六/周日 = 0
        s.setWeekdayCoef(new double[]{1.4, 1.4, 1.4, 1.4, 1.4, 0, 0});
        s.setWeekdayEnabled(true);
        // AS_OF=2026-08-05 是周三 → 未来 2 天 = 周四+周五(都是工作日)
        ReplenishCalc.PurchaseCalc c = ReplenishCalc.purchaseSuggest(
                s, 1, BigDecimal.ONE, new BigDecimal("0.95"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, new BigDecimal("0.5"), BigDecimal.ONE, AS_OF);
        assertThat(c.isWeekdayApplied()).isTrue();
        assertThat(c.getCycleDemand()).isEqualByComparingTo("28"); // 10×1.4×2,≠ 均值版 20
    }

    /** 样本不足(<4 周):星期系数禁用,周期需求退回 d̄×(R+L) */
    @Test
    void weekdayCoefFallbackWhenInsufficientSamples() {
        DemandStats s = stats("10", "0", 20); // 只有 20 天样本
        s.setWeekdayEnabled(false);
        ReplenishCalc.PurchaseCalc c = ReplenishCalc.purchaseSuggest(
                s, 1, BigDecimal.ONE, new BigDecimal("0.95"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, new BigDecimal("0.5"), BigDecimal.ONE, AS_OF);
        assertThat(c.isWeekdayApplied()).isFalse();
        assertThat(c.getCycleDemand()).isEqualByComparingTo("20"); // 10×(1+1)
    }

    // ============================== 6. 机器侧 par level ==============================

    /** 补满货道:建议 = 容量 − 机内现有;存销比/售罄日/紧急度 */
    @Test
    void machineParLevel() {
        ReplenishCalc.MachineCalc c = ReplenishCalc.machineSuggest(
                new BigDecimal("56"), new BigDecimal("4"), new BigDecimal("3.2"), null, AS_OF);
        assertThat(c.getTargetLevel()).isEqualByComparingTo("56");
        assertThat(c.getSuggestQty()).isEqualByComparingTo("52");
        assertThat(c.getStockDays()).isEqualByComparingTo("1.3");            // 4/3.2
        assertThat(c.getSelloutDate()).isEqualTo(AS_OF.plusDays(1));         // floor(4/3.2)=1
        assertThat(c.getUrgency()).isEqualTo("3天内");
    }

    /** 缺货(机内 ≤0):紧急度=缺货,负数按 0 → 建议 = 满容量(货道容量硬约束上限) */
    @Test
    void machineOutOfStock() {
        ReplenishCalc.MachineCalc c = ReplenishCalc.machineSuggest(
                new BigDecimal("30"), new BigDecimal("-5"), new BigDecimal("2"), null, AS_OF);
        assertThat(c.getUrgency()).isEqualTo("缺货");
        assertThat(c.getSuggestQty()).isEqualByComparingTo("30"); // 不会补出 35(容量硬约束)
    }

    /** 临期约束:机内上限 = 日均×保质期×0.5;上限 < 容量 → 压低目标 + 提示 */
    @Test
    void machineExpireCap() {
        // 日均 0.4/天 × 保质期 30 天 × 0.5 = 6 < 容量 20
        ReplenishCalc.MachineCalc c = ReplenishCalc.machineSuggest(
                new BigDecimal("20"), new BigDecimal("2"), new BigDecimal("0.4"), 30, AS_OF);
        assertThat(c.isExpireCapped()).isTrue();
        assertThat(c.getExpireLimit()).isEqualByComparingTo("6");
        assertThat(c.getTargetLevel()).isEqualByComparingTo("6");
        assertThat(c.getSuggestQty()).isEqualByComparingTo("4"); // 6−2
    }

    /** 无保质期数据:跳过临期约束 */
    @Test
    void machineNoShelfLifeSkipsExpireCap() {
        ReplenishCalc.MachineCalc c = ReplenishCalc.machineSuggest(
                new BigDecimal("20"), new BigDecimal("2"), new BigDecimal("0.4"), null, AS_OF);
        assertThat(c.isExpireCapped()).isFalse();
        assertThat(c.getExpireLimit()).isNull();
        assertThat(c.getSuggestQty()).isEqualByComparingTo("18");
    }
}
