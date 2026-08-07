package top.aole.vend.modules.replenish.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.replenish.domain.DemandStats;
import top.aole.vend.modules.replenish.mapper.DemandQueryMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求统计引擎(M2-1):按 SKU(全局 + 分机器)滚动计算
 * 日均 d̄(近 28 天移动平均)/ σd / 星期系数(样本≥4周才启用)/ 慢销判定(日均<1.5)。
 *
 * 口径:
 * - 数据源 sale_record,order_type IN (正常,兑换),不含测试(§13 补货口径);
 * - 统计窗口锚定「数据截至日」(最后一笔出货的业务日期),不是今天——
 *   数据晚导几天不稀释日均(数据新鲜度另有水印提示);
 * - 缺销自然日按 0 计;数据不满 28 天按实际跨度算日均(样本不足 → 星期系数禁用,置信降档)。
 */
@Service
@RequiredArgsConstructor
public class DemandStatsService {

    private final DemandQueryMapper demandQueryMapper;

    /** 数据截至日;库里没有任何出货记录时返回 null */
    public LocalDate dataAsOf() {
        return demandQueryMapper.dataAsOf();
    }

    /** 一次取数的统计快照:全局 + 分机器 */
    public static class Snapshot {
        /** 数据截至日(统计窗口末端) */
        public LocalDate asOf;
        /** productId → 全局统计 */
        public Map<Long, DemandStats> global = new LinkedHashMap<>();
        /** machineId → (productId → 分机器统计) */
        public Map<Long, Map<Long, DemandStats>> byMachine = new LinkedHashMap<>();
    }

    /** 计算全量统计快照(窗口 = [asOf-27, asOf] 共 28 天) */
    public Snapshot compute() {
        Snapshot snap = new Snapshot();
        LocalDate asOf = dataAsOf();
        if (asOf == null) {
            return snap;
        }
        snap.asOf = asOf;
        LocalDate start = asOf.minusDays(DemandStats.WINDOW_DAYS - 1L);
        LocalDate endExclusive = asOf.plusDays(1);

        // 每 SKU 首笔出货日:决定实际样本天数
        Map<Long, LocalDate> firstDay = new HashMap<>();
        for (Map<String, Object> row : demandQueryMapper.firstSaleDay()) {
            firstDay.put(num(row.get("productId")), LocalDate.parse(row.get("firstDay").toString()));
        }

        // 全局:productId → (日期 → 销量)
        Map<Long, Map<LocalDate, BigDecimal>> globalDaily = new LinkedHashMap<>();
        for (Map<String, Object> row : demandQueryMapper.dailyGlobal(start, endExclusive)) {
            globalDaily.computeIfAbsent(num(row.get("productId")), k -> new HashMap<>())
                    .put(LocalDate.parse(row.get("d").toString()), new BigDecimal(row.get("qty").toString()));
        }
        for (Map.Entry<Long, Map<LocalDate, BigDecimal>> e : globalDaily.entrySet()) {
            snap.global.put(e.getKey(),
                    buildStats(e.getKey(), null, e.getValue(), firstDay.get(e.getKey()), start, asOf));
        }

        // 分机器
        Map<Long, Map<Long, Map<LocalDate, BigDecimal>>> machineDaily = new LinkedHashMap<>();
        for (Map<String, Object> row : demandQueryMapper.dailyByMachine(start, endExclusive)) {
            machineDaily.computeIfAbsent(num(row.get("machineId")), k -> new LinkedHashMap<>())
                    .computeIfAbsent(num(row.get("productId")), k -> new HashMap<>())
                    .put(LocalDate.parse(row.get("d").toString()), new BigDecimal(row.get("qty").toString()));
        }
        for (Map.Entry<Long, Map<Long, Map<LocalDate, BigDecimal>>> me : machineDaily.entrySet()) {
            Map<Long, DemandStats> perProduct = new LinkedHashMap<>();
            for (Map.Entry<Long, Map<LocalDate, BigDecimal>> pe : me.getValue().entrySet()) {
                perProduct.put(pe.getKey(),
                        buildStats(pe.getKey(), me.getKey(), pe.getValue(), firstDay.get(pe.getKey()), start, asOf));
            }
            snap.byMachine.put(me.getKey(), perProduct);
        }
        return snap;
    }

    /**
     * 单序列统计(包访问,单测直打):
     * 窗口 = [max(start, 首销日), asOf],缺销日补 0;σ 为总体标准差;
     * 星期系数 = 该周几均值 ÷ 全周均值,仅满 28 天窗口(每周几 4 个样本)且日均>0 时启用。
     */
    public static DemandStats buildStats(Long productId, Long machineId, Map<LocalDate, BigDecimal> daily,
                                  LocalDate firstSaleDay, LocalDate windowStart, LocalDate asOf) {
        DemandStats s = new DemandStats();
        s.setProductId(productId);
        s.setMachineId(machineId);

        LocalDate effStart = windowStart;
        if (firstSaleDay != null && firstSaleDay.isAfter(windowStart)) {
            effStart = firstSaleDay;
        }
        int sampleDays = (int) ChronoUnit.DAYS.between(effStart, asOf) + 1;
        if (sampleDays < 1) {
            sampleDays = 1;
        }
        s.setSampleDays(sampleDays);

        // 逐日序列(缺销日 0)
        double[] series = new double[sampleDays];
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < sampleDays; i++) {
            BigDecimal q = daily.get(effStart.plusDays(i));
            if (q != null) {
                series[i] = q.doubleValue();
                total = total.add(q);
            }
        }
        s.setTotalQty(total);
        BigDecimal avg = total.divide(new BigDecimal(sampleDays), 3, RoundingMode.HALF_UP);
        s.setAvgDaily(avg);

        double mean = avg.doubleValue();
        double sq = 0;
        for (double v : series) {
            sq += (v - mean) * (v - mean);
        }
        double sigma = Math.sqrt(sq / sampleDays);
        s.setSigmaDaily(BigDecimal.valueOf(sigma).setScale(3, RoundingMode.HALF_UP));

        // 星期系数:满窗(28 天 = 每周几 4 个样本)才启用
        if (sampleDays >= DemandStats.WINDOW_DAYS && mean > 0) {
            double[] sum = new double[7];
            int[] cnt = new int[7];
            for (int i = 0; i < sampleDays; i++) {
                DayOfWeek dow = effStart.plusDays(i).getDayOfWeek();
                sum[dow.getValue() - 1] += series[i];
                cnt[dow.getValue() - 1]++;
            }
            double[] coef = new double[7];
            boolean ok = true;
            for (int d = 0; d < 7; d++) {
                if (cnt[d] < 4) {
                    ok = false;
                    break;
                }
                coef[d] = (sum[d] / cnt[d]) / mean;
            }
            if (ok) {
                s.setWeekdayCoef(coef);
                s.setWeekdayEnabled(true);
            }
        }
        return s;
    }

    private static Long num(Object o) {
        return ((Number) o).longValue();
    }
}
