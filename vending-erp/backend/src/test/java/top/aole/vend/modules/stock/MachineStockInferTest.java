package top.aole.vend.modules.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验收组4:机器库存推算——最近快照 + 快照后按业务时间戳增量(转移+ / 出货−);
 * 出货口径=正常+兑换不含测试(P0-3);乱序导入(后导入的早时间戳)也算对(穿行场景11)。
 */
class MachineStockInferTest extends BaseIntegrationTest {

    private static final Long P = 601L;
    private static final Long M = 701L;

    @Test
    @DisplayName("快照10 + 转移+5 − 出货(正常2+兑换1,测试5不计) = 12")
    void snapshotPlusIncrements() {
        LocalDate day = LocalDate.now();
        LocalDateTime t0 = day.atTime(8, 0);

        stockWarehouse(P, "100");
        // 锚点:8:00 快照 10
        stockService.recordMachineSnapshot(M, P, "A1", new BigDecimal("10"),
                MachineStockSnapshot.SRC_BACKEND_PAGE, t0, OP);
        // 9:00 导入转移单 +5(业务时间戳 9:00)
        confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT, day, false,
                day.atTime(9, 0), new Object[]{P, "5", "3.0"});
        // 出货:10:00 正常2、11:00 兑换1、12:00 测试5(测试不计,P0-3)
        insertSale(M, P, "2", "正常", day.atTime(10, 0));
        insertSale(M, P, "1", "兑换", day.atTime(11, 0));
        insertSale(M, P, "5", "测试", day.atTime(12, 0));

        assertEquals(0, stockService.getMachineStock(M, P).compareTo(new BigDecimal("12")),
                "10 + 5 − (2+1) = 12,测试单不参与");

        // 全机汇总口径一致
        Map<Long, BigDecimal> all = stockService.getMachineStockAll(M);
        assertEquals(0, all.get(P).compareTo(new BigDecimal("12")));
    }

    @Test
    @DisplayName("乱序导入:后导入的早时间戳数据按 biz_time 归位——快照前不计,快照后计入")
    void outOfOrderImportStillCorrect() {
        LocalDate day = LocalDate.now();

        stockWarehouse(P, "100");
        // 锚点:10:00 快照 10(先落快照)
        stockService.recordMachineSnapshot(M, P, "A1", new BigDecimal("10"),
                MachineStockSnapshot.SRC_REPLENISH, day.atTime(10, 0), OP);

        // 之后才"补导"历史数据(模拟漏导3天后补导,写入顺序 ≠ 业务顺序):
        // ① 出货 9:00(早于快照)→ 不该计入
        insertSale(M, P, "4", "正常", day.atTime(9, 0));
        // ② 出货 10:30(晚于快照)→ 计入 −3
        insertSale(M, P, "3", "正常", day.atTime(10, 30));
        // ③ 转移单业务时间 11:00(晚于快照)→ 计入 +6(虽然此刻才导入确认)
        confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT, day, false,
                day.atTime(11, 0), new Object[]{P, "6", "3.0"});
        // ④ 转移单业务时间 8:00(早于快照,补导更早的历史补货)→ 不该计入
        confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT, day, false,
                day.atTime(8, 0), new Object[]{P, "7", "3.0"});

        assertEquals(0, stockService.getMachineStock(M, P).compareTo(new BigDecimal("13")),
                "10 − 3 + 6 = 13:快照前的出货4/补货7均不计,与导入顺序无关");
    }
}
