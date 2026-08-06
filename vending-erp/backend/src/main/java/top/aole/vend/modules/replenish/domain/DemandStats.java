package top.aole.vend.modules.replenish.domain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单 SKU 需求统计(M2-1 输出):日均 d̄ / σd / 星期系数 / 慢销判定。
 * 口径:sale_record 正常+兑换(不含测试/退款/线下补录),近 28 天移动平均,缺销日按 0 计。
 */
@Data
public class DemandStats {

    /** 慢销判定阈值:日均 < 1.5 件不套 (R,S) 公式 */
    public static final BigDecimal SLOW_THRESHOLD = new BigDecimal("1.5");

    /** 统计窗口满窗天数 */
    public static final int WINDOW_DAYS = 28;

    private Long productId;

    /** 机器维度统计时填,全局统计为 null */
    private Long machineId;

    /** 实际参与统计的天数(数据不满 28 天时 = 有数据的自然日跨度) */
    private int sampleDays;

    /** 窗口内总销量 */
    private BigDecimal totalQty = BigDecimal.ZERO;

    /** 日均销量 d̄ = 总销量 / sampleDays */
    private BigDecimal avgDaily = BigDecimal.ZERO;

    /** 日销量标准差 σd(总体标准差,缺销日按 0 计) */
    private BigDecimal sigmaDaily = BigDecimal.ZERO;

    /**
     * 星期系数[7]:下标 0=周一 … 6=周日,系数 = 该周几均值 ÷ 全周均值。
     * 仅当每个周几样本 ≥4(即满 28 天窗口)才启用;未启用为 null。
     */
    private double[] weekdayCoef;

    /** 星期系数是否启用(样本 ≥4 周) */
    private boolean weekdayEnabled;

    /** 慢销品:日均 < 1.5 */
    public boolean isSlow() {
        return avgDaily.compareTo(SLOW_THRESHOLD) < 0;
    }

    /** 规则置信 = 数据完备度 = 样本天数/28(封顶 1) */
    public BigDecimal confidence() {
        if (sampleDays >= WINDOW_DAYS) {
            return BigDecimal.ONE;
        }
        return new BigDecimal(sampleDays)
                .divide(new BigDecimal(WINDOW_DAYS), 4, java.math.RoundingMode.HALF_UP);
    }
}
