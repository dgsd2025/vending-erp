package top.aole.vend.modules.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收组5:碰撞检查(P0-3)与预挂单冲抵(P0-4 骨架)——
 * 同机器+SKU+日期已存在导入生成的出库上架单 → 返回冲突;手工单确认被拦;
 * 导入单可冲抵在先的手工预挂单并释放仓库锁。
 */
class TransferCollisionTest extends BaseIntegrationTest {

    private static final Long P = 801L;
    private static final Long M = 901L;

    @Test
    @DisplayName("同机器+SKU+日期已有导入转移单 → 返回冲突;换SKU/换日期 → 无冲突")
    void collisionDetected() {
        LocalDate day = LocalDate.now();
        stockWarehouse(P, "100");
        Long importDocId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                day, false, null, new Object[]{P, "10", "3.0"});

        List<Long> hit = stockService.checkManualTransferCollision(M, P, day);
        assertEquals(1, hit.size());
        assertEquals(importDocId, hit.get(0));

        assertTrue(stockService.checkManualTransferCollision(M, 999998L, day).isEmpty(),
                "不同 SKU 不冲突");
        assertTrue(stockService.checkManualTransferCollision(M, P, day.minusDays(1)).isEmpty(),
                "不同日期不冲突");
        assertTrue(stockService.checkManualTransferCollision(999997L, P, day).isEmpty(),
                "不同机器不冲突");
    }

    @Test
    @DisplayName("后台已有出货 → 手工转移单确认被拦(P0-3 硬规则)")
    void manualConfirmBlockedByCollision() {
        LocalDate day = LocalDate.now();
        stockWarehouse(P, "100");
        confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT, day, false, null,
                new Object[]{P, "10", "3.0"});

        Long manualId = docService.createDoc(
                req(DocType.TRANSFER_OUT, M, DocService.SOURCE_MANUAL, day,
                        new Object[]{P, "10", "3.0"}), OP);
        docService.submit(manualId, OP);
        BizException e = assertThrows(BizException.class, () -> docService.confirm(manualId, OP, false));
        assertTrue(e.getMessage().contains("禁止手工再录"), e.getMessage());
    }

    @Test
    @DisplayName("冲抵骨架:导入单确认后 matchPendingTransfer → 手工预挂单作废+释放仓库锁,不双扣")
    void importMatchesPendingTransfer() {
        LocalDate day = LocalDate.now();
        stockWarehouse(P, "100");

        // 先有手工预挂单(锁仓库 -20)
        Long manualId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_MANUAL,
                day, false, null, new Object[]{P, "20", "3.0"});
        assertEquals(DocStatus.PRE_PENDING, docService.getDoc(manualId).getHead().getDocStatus());
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("80")));

        // 次日导入后台补货记录生成真转移单(-20 / 机器+20)
        Long importId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                day, false, day.atTime(14, 0), new Object[]{P, "20", "3.0"});

        // 冲抵:预挂单作废、matched_doc_id 回指、仓库锁释放 → 最终只扣一次
        List<Long> matched = stockService.matchPendingTransfer(importId, OP);
        assertEquals(1, matched.size());
        assertEquals(manualId, matched.get(0));
        assertEquals(DocStatus.VOID, docService.getDoc(manualId).getHead().getDocStatus());
        assertEquals(importId, docService.getDoc(manualId).getHead().getMatchedDocId());
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("80")),
                "冲抵后净扣一次:100 − 20(导入单),预挂单锁已释放,不双扣");
        assertEquals(0, stockService.getMachineStock(M, P).compareTo(new BigDecimal("20")),
                "机器侧只有导入单写入的 +20");
    }
}
