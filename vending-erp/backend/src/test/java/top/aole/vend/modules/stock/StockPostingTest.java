package top.aole.vend.modules.stock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;
import top.aole.vend.modules.stock.service.StockService;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收组2:库存写手——采购入库确认→仓库+;出库上架确认→仓库−机器+;
 * 流水只由单据产生(设计上不存在公开写方法);预挂单只锁仓库侧。
 */
class StockPostingTest extends BaseIntegrationTest {

    private static final Long P = 201L;
    private static final Long M = 301L;

    @Autowired
    private StockLedgerMapper ledgerMapper;

    @Test
    @DisplayName("采购入库确认 → 仓库 +100;流水挂单据号;批量查询一致")
    void purchaseInAddsWarehouse() {
        Long docId = stockWarehouse(P, "100");
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("100")));

        List<StockLedger> rows = ledgerMapper.selectList(
                new LambdaQueryWrapper<StockLedger>().eq(StockLedger::getDocId, docId));
        assertEquals(1, rows.size());
        assertEquals(StockLedger.LOC_WAREHOUSE, rows.get(0).getLocationType());
        assertEquals(0, rows.get(0).getBalanceQty().compareTo(new BigDecimal("100")));

        Map<Long, BigDecimal> batch = stockService.getWarehouseStockBatch(asList(P, 999999L));
        assertEquals(0, batch.get(P).compareTo(new BigDecimal("100")));
        assertEquals(0, batch.get(999999L).compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("导入出库上架确认 → 仓库−30 机器+30(两条流水一次事务)")
    void importTransferMovesBothSides() {
        stockWarehouse(P, "100");
        Long docId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{P, "30", "3.5"});

        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("70")));
        assertEquals(0, stockService.getMachineStock(M, P).compareTo(new BigDecimal("30")));

        List<StockLedger> rows = ledgerMapper.selectList(
                new LambdaQueryWrapper<StockLedger>().eq(StockLedger::getDocId, docId));
        assertEquals(2, rows.size(), "转移单=仓库/机器各一条流水");
    }

    @Test
    @DisplayName("手工出库上架确认 → 预挂单:只锁仓库侧,不写机器库存(P0-4)")
    void manualTransferBecomesPrePendingWarehouseOnly() {
        stockWarehouse(P, "50");
        Long docId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{P, "20", "3.5"});

        assertEquals(DocStatus.PRE_PENDING, docService.getDoc(docId).getHead().getDocStatus());
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("30")),
                "仓库侧应被锁定(-20)");
        assertEquals(0, stockService.getMachineStock(M, P).compareTo(BigDecimal.ZERO),
                "预挂单不许写机器库存");

        // 超48h转正(定时钩子 M1-3;此处手动触发) → 补机器侧,仓库不再重复扣
        docService.promotePendingTransfer(docId, OP, null);
        assertEquals(DocStatus.CONFIRMED, docService.getDoc(docId).getHead().getDocStatus());
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("30")));
        assertEquals(0, stockService.getMachineStock(M, P).compareTo(new BigDecimal("20")));
    }

    @Test
    @DisplayName("流水只由单据产生:写手类非 public,StockService/DocService 无公开裸写流水方法")
    void ledgerHasNoPublicWriteApi() throws Exception {
        Class<?> writer = Class.forName("top.aole.vend.modules.stock.service.StockLedgerWriter");
        assertFalse(Modifier.isPublic(writer.getModifiers()),
                "StockLedgerWriter 必须包私有,防止绕过单据直改库存");

        for (Method m : StockService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                assertNotEquals(StockLedger.class, p,
                        "StockService 公开方法不许直接收 StockLedger:" + m.getName());
            }
            String n = m.getName().toLowerCase();
            assertFalse(n.contains("insertledger") || n.contains("writeledger")
                    || n.contains("updatestock") || n.contains("setstock"),
                    "发现疑似裸写库存的公开方法:" + m.getName());
        }
    }
}
