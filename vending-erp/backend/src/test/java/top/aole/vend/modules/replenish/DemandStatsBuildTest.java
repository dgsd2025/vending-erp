package top.aole.vend.modules.replenish;

import org.junit.jupiter.api.Test;
import top.aole.vend.modules.replenish.domain.DemandStats;
import top.aole.vend.modules.replenish.service.DemandStatsService;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 需求统计纯逻辑单测(M2-1):日均/σ/星期系数/慢销/样本不足退化。
 * buildStats 为包内可见静态方法,直接打(缺销日按 0 计)。
 */
class DemandStatsBuildTest {

    /** 窗口末端(周三) */
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);
    private static final LocalDate START = AS_OF.minusDays(27);

    // ============================== 日均 / σ / 缺销补 0 ==============================

    /** 28 天每天 20:d̄=20,σ=0,星期系数全 1 且启用 */
    @Test
    void flatSeries() {
        Map<LocalDate, BigDecimal> daily = new HashMap<>();
        for (int i = 0; i < 28; i++) {
            daily.put(START.plusDays(i), new BigDecimal("20"));
        }
        DemandStats s = statsOf(daily, START);
        assertThat(s.getSampleDays()).isEqualTo(28);
        assertThat(s.getAvgDaily()).isEqualByComparingTo("20");
        assertThat(s.getSigmaDaily()).isEqualByComparingTo("0");
        assertThat(s.isWeekdayEnabled()).isTrue();
        assertThat(s.getWeekdayCoef()[0]).isEqualTo(1.0);
        assertThat(s.isSlow()).isFalse();
        assertThat(s.confidence()).isEqualByComparingTo("1");
    }

    /** 缺销日按 0 计:28 天只有 14 天各卖 28 → d̄ = 14×28/28 = 14,σ>0 */
    @Test
    void zeroFillMissingDays() {
        Map<LocalDate, BigDecimal> daily = new HashMap<>();
        for (int i = 0; i < 28; i += 2) {
            daily.put(START.plusDays(i), new BigDecimal("28"));
        }
        DemandStats s = statsOf(daily, START);
        assertThat(s.getAvgDaily()).isEqualByComparingTo("14");
        assertThat(s.getSigmaDaily()).isEqualByComparingTo("14"); // 一半 28 一半 0 → σ=14
    }

    // ============================== 星期系数 ==============================

    /** 周末为 0 的园区模式:周末系数=0,工作日系数=7/5=1.4 */
    @Test
    void weekdayCoefCampusPattern() {
        Map<LocalDate, BigDecimal> daily = new HashMap<>();
        for (int i = 0; i < 28; i++) {
            LocalDate d = START.plusDays(i);
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                daily.put(d, new BigDecimal("7"));
            }
        }
        DemandStats s = statsOf(daily, START);
        assertThat(s.getAvgDaily()).isEqualByComparingTo("5"); // 20天×7/28
        assertThat(s.isWeekdayEnabled()).isTrue();
        assertThat(s.getWeekdayCoef()[DayOfWeek.MONDAY.getValue() - 1]).isEqualTo(1.4);
        assertThat(s.getWeekdayCoef()[DayOfWeek.SATURDAY.getValue() - 1]).isEqualTo(0.0);
    }

    /** 样本不足退化:首销日只有 10 天前(<28)→ 按实际跨度算日均,星期系数禁用,置信降档 */
    @Test
    void insufficientSampleFallback() {
        LocalDate firstDay = AS_OF.minusDays(9); // 只有 10 天
        Map<LocalDate, BigDecimal> daily = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            daily.put(firstDay.plusDays(i), new BigDecimal("6"));
        }
        DemandStats s = DemandStatsService.buildStats(1L, null, daily, firstDay, START, AS_OF);
        assertThat(s.getSampleDays()).isEqualTo(10);
        assertThat(s.getAvgDaily()).isEqualByComparingTo("6"); // 60/10,不被 28 稀释
        assertThat(s.isWeekdayEnabled()).isFalse();            // 每周几样本 <4
        assertThat(s.confidence()).isEqualByComparingTo("0.3571"); // 10/28
    }

    // ============================== 慢销判定 ==============================

    /** 日均 1.0 < 1.5 → 慢销;1.5 及以上不算 */
    @Test
    void slowMoverThreshold() {
        Map<LocalDate, BigDecimal> daily = new HashMap<>();
        for (int i = 0; i < 28; i++) {
            daily.put(START.plusDays(i), BigDecimal.ONE);
        }
        assertThat(statsOf(daily, START).isSlow()).isTrue();

        Map<LocalDate, BigDecimal> daily2 = new HashMap<>();
        for (int i = 0; i < 28; i++) {
            daily2.put(START.plusDays(i), new BigDecimal("1.5"));
        }
        assertThat(statsOf(daily2, START).isSlow()).isFalse();
    }

    private DemandStats statsOf(Map<LocalDate, BigDecimal> daily, LocalDate firstDay) {
        return DemandStatsService.buildStats(1L, null, daily, firstDay, START, AS_OF);
    }
}
