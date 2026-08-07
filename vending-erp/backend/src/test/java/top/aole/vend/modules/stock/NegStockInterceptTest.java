package top.aole.vend.modules.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收组3:负库存——手工拦截(错误信息带当前库存/请求量)、exempt 放行带标记、
 * 手工单不许豁免(P1-8)、期初天然豁免。
 */
class NegStockInterceptTest extends BaseIntegrationTest {

    private static final Long P = 401L;
    private static final Long M = 501L;

    @Autowired
    private StockLedgerMapper ledgerMapper;

    @Test
    @DisplayName("手工单确认导致仓库负库存 → 拦截,报错带当前库存与请求量,库存不变")
    void manualDocIntercepted() {
        stockWarehouse(P, "10");
        Long docId = docService.createDoc(
                req(DocType.LOSS_OUT, null, DocService.SOURCE_MANUAL, LocalDate.now(),
                        new Object[]{P, "15", "3.0"}), OP);
        docService.submit(docId, OP);

        BizException e = assertThrows(BizException.class, () -> docService.confirm(docId, OP, false));
        assertTrue(e.getMessage().contains("负库存拦截"), e.getMessage());
        assertTrue(e.getMessage().contains("10"), "报错必须带当前库存:" + e.getMessage());
        assertTrue(e.getMessage().contains("15"), "报错必须带请求量:" + e.getMessage());

        // 拦截生效:一条流水都没写、库存没动。
        // (状态回滚发生在事务边界;测试类自身在同一事务内,不在此断言 head 状态——
        //  生产路径 confirm 的 @Transactional 会把状态更新连同过账一起回滚)
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("10")));
        assertEquals(0, ledgerMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<top.aole.vend.modules.stock.domain.entity.StockLedger>()
                        .eq(top.aole.vend.modules.stock.domain.entity.StockLedger::getDocId, docId)),
                "拦截时不许写任何流水");
    }

    @Test
    @DisplayName("导入转移单 exempt=true → 放行为负库存,neg_stock_exempt 标记留档(P1-8)")
    void importDocExemptPasses() {
        stockWarehouse(P, "10");
        Long docId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                LocalDate.now(), true, null, new Object[]{P, "25", "3.0"});

        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("-15")),
                "豁免放行后仓库允许负库存(亮'待补录采购'红灯)");
        assertTrue(Boolean.TRUE.equals(docService.getDoc(docId).getHead().getNegStockExempt()),
                "豁免必须留 neg_stock_exempt 标记");
    }

    @Test
    @DisplayName("手工单请求豁免 → 直接拒绝(仅导入通道/红冲/期初可豁免)")
    void manualDocCannotExempt() {
        Long docId = docService.createDoc(
                req(DocType.LOSS_OUT, null, DocService.SOURCE_MANUAL, LocalDate.now(),
                        new Object[]{P, "5", "3.0"}), OP);
        docService.submit(docId, OP);
        BizException e = assertThrows(BizException.class, () -> docService.confirm(docId, OP, true));
        assertTrue(e.getMessage().contains("手工单据不允许豁免"), e.getMessage());
    }

    @Test
    @DisplayName("期初单:确认后写 stock_ledger,天然豁免负库存")
    void openingDocWritesLedgerAndExempt() {
        Long docId = confirmedDoc(DocType.OPENING, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{P, "88", "2.8"});
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("88")));
        assertTrue(Boolean.TRUE.equals(docService.getDoc(docId).getHead().getNegStockExempt()),
                "期初单建单即带豁免标记");
    }
}
