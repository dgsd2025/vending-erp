package top.aole.vend.regression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.replenish.service.DemandStatsService;
import top.aole.vend.modules.replenish.service.ReplenishEngine;
import top.aole.vend.modules.report.mapper.ReportQueryMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-7 全链路场景21:数据新鲜度(vend_test_regression 独立库,每例事务回滚)。
 *
 * 主线:导入批次的业务时间 → dataAsOf(数据截至水印)计算 → 超 3 天判定。
 *   ① 纯逻辑边界:staleDays = DAYS.between(asOf, 参考日);> 3 天 = 陈旧(3 天整不算,严格大于);
 *   ② 真链路:导入一批后台出货明细,dataAsOf = 该批最晚业务日(三源 GREATEST),
 *      补货引擎 staleDays 与之一致,超 3 天判陈旧;
 *   ③ 新鲜数据进来(当天出货)→ 水印前移到今天 → staleDays 归 0,不再陈旧。
 */
class Scenario21DataFreshnessTest extends RegressionSupport {

    /** 数据陈旧阈值:数据截至日距今 > 3 天判定为陈旧。 */
    private static final int STALE_THRESHOLD_DAYS = 3;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private DemandStatsService demandStatsService;
    @Autowired private ReplenishEngine replenishEngine;
    @Autowired private ReportQueryMapper reportQueryMapper;

    /** 超 3 天判定(纯逻辑;严格大于阈值)。 */
    private static boolean isStale(LocalDate asOf, LocalDate ref) {
        return ChronoUnit.DAYS.between(asOf, ref) > STALE_THRESHOLD_DAYS;
    }

    // ============================== ① 纯逻辑边界 ==============================

    @Test
    @DisplayName("超3天判定(纯逻辑):0/3 天不陈旧,4/5 天陈旧(严格大于阈值,边界锚定固定参考日不受运行时刻影响)")
    void staleJudgementBoundary() {
        LocalDate ref = LocalDate.of(2026, 8, 20); // 固定参考日,消除跨零点抖动
        assertEquals(3, STALE_THRESHOLD_DAYS, "阈值=3 天");
        assertFalse(isStale(ref, ref), "当天(0 天)→ 新鲜");
        assertFalse(isStale(ref.minusDays(3), ref), "整 3 天 → 不陈旧(严格大于)");
        assertTrue(isStale(ref.minusDays(4), ref), "4 天 → 陈旧");
        assertTrue(isStale(ref.minusDays(5), ref), "5 天 → 陈旧");
        // 参考日提前一天,边界随之平移(纯函数,与真实时间无关)
        assertFalse(isStale(ref.minusDays(3), ref.minusDays(1)), "同锚点参考日前移一天 → 2 天,不陈旧");
    }

    // ============================== ② 真链路:导入批次 → dataAsOf → 陈旧 ==============================

    @Test
    @DisplayName("导入批次业务时间 → dataAsOf 三源取 GREATEST;5 天前的批次 → 补货引擎 staleDays=5,超3天判陈旧")
    void importBatchDrivesDataAsOfAndStale() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate batchDay = today.minusDays(5);

        Machine m = machine("场景21-陈旧机");
        Product p = product("场景21-东鹏", "S21RG001", null);
        alias("S21RG001", "东鹏特饮场景21", p.getId());
        // 仓库垫底放 10 天前(过去时间戳,不污染水印);机器锚点放 6 天前
        LocalDateTime warehouseT = today.minusDays(10).atTime(8, 0);
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                warehouseT.toLocalDate(), false, warehouseT, new Object[]{p.getId(), "50", "3.5"});
        importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 3, "东鹏特饮场景21", "S21RG001", 0, 10, 10, "小邱",
                        today.minusDays(6).atTime(10, 0).format(TS)},
        }));
        // 出货明细批次:最晚业务时间 = 5 天前 → 成为数据截至水印
        importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"S21-A", "东鹏特饮场景21", "S21RG001", 1, 3, m.getDeviceId(), 5.0, "正常订单", "微信",
                        batchDay.atTime(9, 0).format(TS)},
                {"S21-B", "东鹏特饮场景21", "S21RG001", 1, 3, m.getDeviceId(), 5.0, "正常订单", "微信",
                        batchDay.atTime(15, 0).format(TS)},
        }));

        // dataAsOf 两个口径都锚到批次最晚业务日(5 天前)
        assertEquals(batchDay, demandStatsService.dataAsOf(), "补货口 dataAsOf = 出货批次最晚业务日");
        assertEquals(batchDay, reportQueryMapper.dataAsOf().toLocalDate(),
                "报表口 dataAsOf(三源 GREATEST)= 5 天前(仓库10天前/机器6天前均更早)");

        // 补货引擎 staleDays 与水印一致(就近重算期望值,避免跨零点抖动)
        LocalDate asOf = demandStatsService.dataAsOf();
        long expected = ChronoUnit.DAYS.between(asOf, LocalDate.now());
        Number staleDays = (Number) replenishEngine.suggestions("machine", null).get("staleDays");
        assertEquals(expected, staleDays.longValue(), "补货引擎 staleDays = DAYS.between(asOf, now)");
        assertTrue(staleDays.longValue() > STALE_THRESHOLD_DAYS, "5 天前批次 → 超3天 → 陈旧");
        assertTrue(isStale(asOf, LocalDate.now()), "纯逻辑判定同样陈旧");
    }

    // ============================== ③ 新鲜数据 → 水印前移 → 归 0 ==============================

    @Test
    @DisplayName("当天新数据进来 → dataAsOf 前移到今天 → staleDays=0,不再陈旧")
    void freshDataResetsFreshness() {
        LocalDate today = LocalDate.now();
        Machine m = machine("场景21-新鲜机");
        Product p = product("场景21-可乐", "S21RG002", null);
        // 当天一笔正常出货(RegressionSupport.sale 直落 sale_record,即导入批次落库后的等价形态)
        sale(m.getId(), p.getId(), "1", "5", "正常", today.atTime(12, 0));

        assertEquals(today, demandStatsService.dataAsOf(), "当天出货 → 数据截至日 = 今天");
        assertEquals(today, reportQueryMapper.dataAsOf().toLocalDate(), "报表口水印同样前移到今天");

        Number staleDays = (Number) replenishEngine.suggestions("machine", null).get("staleDays");
        assertEquals(0L, staleDays.longValue(), "当天数据 → staleDays=0");
        assertFalse(isStale(today, LocalDate.now()), "0 天 → 不陈旧");
    }
}
