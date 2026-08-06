package top.aole.vend.modules.replenish.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * (R,S) 补货纯计算器(M2-2 核心,无任何 IO/Spring 依赖,单测直接打):
 * - 机器侧:par level 补满货道(建议 = 货道容量合计 − 机内现有),不套 (R,S);
 * - 仓库侧:S = d̄×(R+L) + Z×σd×√(R+L),Q = S − (仓库+机内+在途);
 * - 慢销品(日均<1.5):不套公式,低于 min 箱补到 max 箱;
 * - 整箱向上取整;星期系数版周期需求 = 未来 R+L 天逐日预测之和。
 * 铁律:LLM 永不算数,数字全部出自本类;每一步进 calc detail。
 */
public final class ReplenishCalc {

    private ReplenishCalc() {
    }

    // ============================== Z 查表 ==============================

    /**
     * 服务水平 → Z 系数查表(调研 §4.1):90%→1.28,95%→1.65,98%→2.05。
     * 常用档位精确匹配(容差 0.005),其余取不低于该水平的最近档(保守偏高)。
     */
    public static BigDecimal zFor(BigDecimal serviceLevel) {
        double p = serviceLevel == null ? 0.95 : serviceLevel.doubleValue();
        double[][] table = {
                {0.80, 0.84}, {0.85, 1.04}, {0.90, 1.28}, {0.93, 1.48},
                {0.95, 1.65}, {0.97, 1.88}, {0.98, 2.05}, {0.99, 2.33},
        };
        for (double[] row : table) {
            if (Math.abs(p - row[0]) < 0.005) {
                return BigDecimal.valueOf(row[1]);
            }
        }
        for (double[] row : table) {
            if (row[0] >= p) {
                return BigDecimal.valueOf(row[1]);
            }
        }
        return BigDecimal.valueOf(2.33);
    }

    // ============================== 机器侧(par level) ==============================

    /** 机器侧计算结果 */
    @Data
    public static class MachineCalc {
        /** 建议补货量 = 目标水位 − max(机内现有,0) */
        private BigDecimal suggestQty;
        /** 目标水位(=货道容量合计,受临期上限压低) */
        private BigDecimal targetLevel;
        /** 存销比 = 机内现有 ÷ 日均(还能卖几天);日均为 0 时 null */
        private BigDecimal stockDays;
        /** 预计售罄日;推不出为 null */
        private LocalDate selloutDate;
        /** 紧急度:缺货/急/3天内/正常 */
        private String urgency;
        /** 临期上限 = 日均×保质期×0.5(无保质期数据为 null) */
        private BigDecimal expireLimit;
        /** 临期约束是否压低了目标水位 */
        private boolean expireCapped;
    }

    /**
     * 机器侧 par level:每天到访补满货道。
     *
     * @param capacityTotal 该机该 SKU 货道容量合计(正常货道)
     * @param machineQty    机内现有(推算值,可为负 → 按 0 参与补货量计算)
     * @param avgDaily      该机该 SKU 日均销量(可为 null=无销量数据)
     * @param shelfLifeDays 保质期天数(null=无数据,跳过临期约束)
     * @param asOf          数据截至日(售罄日推算锚点)
     */
    public static MachineCalc machineSuggest(BigDecimal capacityTotal, BigDecimal machineQty,
                                             BigDecimal avgDaily, Integer shelfLifeDays, LocalDate asOf) {
        MachineCalc c = new MachineCalc();
        BigDecimal current = machineQty == null ? BigDecimal.ZERO : machineQty;
        BigDecimal effCurrent = current.max(BigDecimal.ZERO);
        BigDecimal target = capacityTotal == null ? BigDecimal.ZERO : capacityTotal;

        // 临期约束:机内上限 = 日均 × 剩余保质期 × 0.5(超限提示并压低目标;无保质期数据跳过)
        if (shelfLifeDays != null && avgDaily != null) {
            BigDecimal limit = avgDaily.multiply(new BigDecimal(shelfLifeDays))
                    .multiply(new BigDecimal("0.5")).setScale(0, RoundingMode.FLOOR);
            c.setExpireLimit(limit);
            if (target.compareTo(limit) > 0) {
                c.setExpireCapped(true);
                target = limit.max(effCurrent);
            }
        }
        c.setTargetLevel(target);
        c.setSuggestQty(target.subtract(effCurrent).max(BigDecimal.ZERO));

        if (avgDaily != null && avgDaily.signum() > 0) {
            BigDecimal days = effCurrent.divide(avgDaily, 1, RoundingMode.HALF_UP);
            c.setStockDays(days);
            c.setSelloutDate(asOf.plusDays(effCurrent.divide(avgDaily, 0, RoundingMode.FLOOR).longValue()));
        }

        if (current.signum() <= 0) {
            c.setUrgency("缺货");
        } else if (c.getStockDays() != null && c.getStockDays().compareTo(BigDecimal.ONE) <= 0) {
            c.setUrgency("急");
        } else if (c.getStockDays() != null && c.getStockDays().compareTo(new BigDecimal("3")) <= 0) {
            c.setUrgency("3天内");
        } else {
            c.setUrgency("正常");
        }
        return c;
    }

    // ============================== 仓库侧((R,S) / 慢销 min-max) ==============================

    /** 仓库侧计算结果 */
    @Data
    public static class PurchaseCalc {
        /** 计算模式:RS公式 / 慢销min-max */
        private String mode;
        /** 周期需求 = d̄×(R+L)(星期系数启用时 = 逐日预测和) */
        private BigDecimal cycleDemand;
        /** 是否用了星期系数逐日预测 */
        private boolean weekdayApplied;
        /** 安全库存 SS = Z×σd×√(R+L) */
        private BigDecimal safetyStock;
        /** 补到水位 S = ceil(周期需求 + SS);慢销 = max 箱水位 */
        private BigDecimal targetS;
        /** 可用量 = 仓库 + 机内 + 在途 */
        private BigDecimal available;
        /** 建议采购量 Q = S − 可用量(<0 归 0) */
        private BigDecimal suggestQty;
        /** 整箱向上取整后数量 */
        private BigDecimal boxRoundQty;
        /** 整箱数 */
        private BigDecimal boxes;
        /** 使用的 Z */
        private BigDecimal z;
    }

    /**
     * 仓库侧采购建议。
     *
     * @param stats        全局需求统计(d̄/σd/星期系数/慢销)
     * @param cycleDaysR   补货周期 R(天)
     * @param leadTimeL    提前期 L(天)
     * @param serviceLevel 服务水平(查 Z)
     * @param warehouseQty 仓库现存
     * @param machineTotal 机内合计(负值按 0 进,待盘点)
     * @param inTransitQty 在途
     * @param boxSpec      箱规(≤1 视为不成箱,不取整)
     * @param slowMinBoxes 慢销最低水位(箱)
     * @param slowMaxBoxes 慢销补到水位(箱)
     * @param asOf         数据截至日(星期系数逐日预测锚点:预测 asOf+1 … asOf+R+L)
     */
    public static PurchaseCalc purchaseSuggest(DemandStats stats, int cycleDaysR, BigDecimal leadTimeL,
                                               BigDecimal serviceLevel, BigDecimal warehouseQty,
                                               BigDecimal machineTotal, BigDecimal inTransitQty,
                                               BigDecimal boxSpec, BigDecimal slowMinBoxes,
                                               BigDecimal slowMaxBoxes, LocalDate asOf) {
        PurchaseCalc c = new PurchaseCalc();
        BigDecimal wh = nz(warehouseQty);
        BigDecimal mc = nz(machineTotal).max(BigDecimal.ZERO);
        BigDecimal transit = nz(inTransitQty);
        BigDecimal available = wh.add(mc).add(transit);
        c.setAvailable(available);
        BigDecimal box = boxSpec == null || boxSpec.compareTo(BigDecimal.ONE) < 0 ? BigDecimal.ONE : boxSpec;

        if (stats.isSlow()) {
            // 慢销:不套公式,低于 min 箱补到 max 箱
            c.setMode("慢销min-max");
            BigDecimal minQty = nz(slowMinBoxes, new BigDecimal("0.5")).multiply(box);
            BigDecimal maxQty = nz(slowMaxBoxes, BigDecimal.ONE).multiply(box);
            c.setTargetS(maxQty);
            if (available.compareTo(minQty) < 0) {
                c.setSuggestQty(maxQty.subtract(available).max(BigDecimal.ZERO));
            } else {
                c.setSuggestQty(BigDecimal.ZERO);
            }
        } else {
            c.setMode("RS公式");
            BigDecimal rl = new BigDecimal(cycleDaysR).add(nz(leadTimeL, BigDecimal.ONE));
            int rlDays = rl.setScale(0, RoundingMode.CEILING).intValue();

            // 周期需求:星期系数启用 → 未来 R+L 天逐日预测和;否则 d̄×(R+L)
            BigDecimal cycleDemand;
            if (stats.isWeekdayEnabled() && stats.getWeekdayCoef() != null) {
                double sum = 0;
                for (int i = 1; i <= rlDays; i++) {
                    DayOfWeek dow = asOf.plusDays(i).getDayOfWeek();
                    sum += stats.getAvgDaily().doubleValue() * stats.getWeekdayCoef()[dow.getValue() - 1];
                }
                cycleDemand = BigDecimal.valueOf(sum).setScale(1, RoundingMode.HALF_UP);
                c.setWeekdayApplied(true);
            } else {
                cycleDemand = stats.getAvgDaily().multiply(rl).setScale(1, RoundingMode.HALF_UP);
            }
            c.setCycleDemand(cycleDemand);

            BigDecimal z = zFor(serviceLevel);
            c.setZ(z);
            BigDecimal ss = z.multiply(nz(stats.getSigmaDaily()))
                    .multiply(BigDecimal.valueOf(Math.sqrt(rl.doubleValue())))
                    .setScale(1, RoundingMode.HALF_UP);
            c.setSafetyStock(ss);

            // S 向上取整到整数(算例:359.6 → 360)
            BigDecimal s = cycleDemand.add(ss).setScale(0, RoundingMode.CEILING);
            c.setTargetS(s);
            c.setSuggestQty(s.subtract(available).max(BigDecimal.ZERO));
        }

        // 整箱向上取整
        if (c.getSuggestQty().signum() > 0) {
            BigDecimal boxes = c.getSuggestQty().divide(box, 0, RoundingMode.CEILING);
            c.setBoxes(boxes);
            c.setBoxRoundQty(boxes.multiply(box));
        } else {
            c.setBoxes(BigDecimal.ZERO);
            c.setBoxRoundQty(BigDecimal.ZERO);
        }
        return c;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nz(BigDecimal v, BigDecimal dft) {
        return v == null ? dft : v;
    }
}
