package top.aole.vend.regression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 穿行场景11:漏导3天/乱序导入(审计结论:通,毛刺=增量推算需按业务时间戳而非导入顺序;P2-12 口径:
 * 机器库存 = 最近快照 + 快照后按 biz_time 的增量(转移+/出货−),与导入顺序无关)。
 *
 * M1 已落地并全测(端到端,导入真链路):乱序补导归位 + 快照前数据不重复计 + 防重幂等。
 */
class Scenario11OutOfOrderImportTest extends RegressionSupport {

    @Test
    @DisplayName("漏导3天后补导(端到端):后导入的早时间戳按 biz_time 归位,机器库存推算与导入顺序无关")
    void backfillOutOfOrderStillCorrect() throws Exception {
        Machine m = machine("漏导的4楼机");
        Product p = product("漏导的东鹏", "RG691101", null);
        alias("RG691101", "东鹏特饮漏导装", p.getId());
        stockWarehouse(p.getId(), "50");

        // 7-01 10:00 补货记录导入:上架10,补货后库存10 → 快照锚点
        importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 3, "东鹏特饮漏导装", "RG691101", 0, 10, 10, "小邱", "2026-07-01 10:00:00"},
        }));
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId())
                .compareTo(new BigDecimal("10")), "锚点=快照10");

        // 先导了 7-03 的出货(顺序颠倒:先导晚的)
        importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"LD301", "东鹏特饮漏导装", "RG691101", 1, 3, m.getDeviceId(), 5.0, "正常订单", "微信", "2026-07-03 09:00:00"},
                {"LD302", "东鹏特饮漏导装", "RG691101", 1, 3, m.getDeviceId(), 5.0, "正常订单", "微信", "2026-07-03 15:00:00"},
        }));
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId())
                .compareTo(new BigDecimal("8")), "10 − 7/03 的 2 = 8");

        // 3天后才补导 7-02 漏掉的出货 + 更早 6-30 的历史单(快照前,不该重复计)
        importFile(ImportBatch.TYPE_SALE, saleFile(new Object[][]{
                {"LD201", "东鹏特饮漏导装", "RG691101", 2, 3, m.getDeviceId(), 10.0, "正常订单", "微信", "2026-07-02 12:00:00"},
                {"LD202", "东鹏特饮漏导装", "RG691101", 1, 3, m.getDeviceId(), 5.0, "正常订单", "微信", "2026-07-02 18:00:00"},
                {"LD101", "东鹏特饮漏导装", "RG691101", 4, 3, m.getDeviceId(), 20.0, "正常订单", "微信", "2026-06-30 12:00:00"},
        }));
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId())
                .compareTo(new BigDecimal("5")),
                "10 −(7/02 的 3 + 7/03 的 2)= 5:补导按 biz_time 归位;6/30 早于快照不重复计");
    }

    @Test
    @DisplayName("乱序的补货记录也归位:晚导入的早时间戳转移单落在快照前 → 不计;快照后 → 计入")
    void outOfOrderReplenishAnchoredByBizTime() throws Exception {
        Machine m = machine("乱序补货机");
        Product p = product("乱序的可乐", "RG691102", null);
        alias("RG691102", "可乐乱序装", p.getId());
        stockWarehouse(p.getId(), "60");

        // 7-05 10:00 补货+8,补货后库存8 → 最新快照锚点
        importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 1, "可乐乱序装", "RG691102", 0, 8, 8, "小邱", "2026-07-05 10:00:00"},
        }));
        // 之后才补导 7-05 11:00 的补货(晚于快照 → 计入 +6;它自己也带快照8+6=14?
        // 后台导出里该行"补货后库存"=14 → 成为更晚的新锚点)
        importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 1, "可乐乱序装", "RG691102", 8, 6, 14, "小邱", "2026-07-05 11:00:00"},
        }));
        // 再补导 7-04 的历史补货(早于两个快照 → 不影响推算结果)
        importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 1, "可乐乱序装", "RG691102", 0, 5, 5, "小邱", "2026-07-04 09:00:00"},
        }));

        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId())
                .compareTo(new BigDecimal("14")), "最新锚点(11:00 快照14)+ 之后无增量 = 14");
        // 仓库账照实累计:60 − 8 − 6 − 5 = 41(仓库是流水账,历史补导照扣)
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("41")));
    }

    @Test
    @DisplayName("重复补导幂等(去重键防线):同一文件再导 → sale 全部 dup 零副作用,机器库存不变")
    void reimportSameFileZeroSideEffect() throws Exception {
        Machine m = machine("重复导入机");
        Product p = product("重复导的红牛", "RG691103", null);
        alias("RG691103", "红牛重复装", p.getId());
        stockWarehouse(p.getId(), "20");
        importFile(ImportBatch.TYPE_REPLENISH, repFile(new Object[][]{
                {m.getDeviceId(), 2, "红牛重复装", "RG691103", 0, 6, 6, "小邱", "2026-07-06 10:00:00"},
        }));

        byte[] sales = saleFile(new Object[][]{
                {"CF001", "红牛重复装", "RG691103", 1, 2, m.getDeviceId(), 6.0, "正常订单", "微信", "2026-07-06 12:00:00"},
        });
        ImportDtos.CommitResp first = importFile(ImportBatch.TYPE_SALE, sales);
        assertEquals(1, first.getRowOk());
        ImportDtos.CommitResp second = importFile(ImportBatch.TYPE_SALE, sales);
        assertEquals(0, second.getRowOk());
        assertEquals(1, second.getRowDup(), "订单号+类型去重,重复导入零副作用");
        assertEquals(0, stockService.getMachineStock(m.getId(), p.getId())
                .compareTo(new BigDecimal("5")), "6−1=5,不因重复导入变4");
    }
}
