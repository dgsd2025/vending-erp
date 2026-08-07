package top.aole.vend.modules.doc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.doc.service.RedFlushService;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.mapper.PurchaseOrderItemMapper;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.purchase.service.PurchaseReceiptService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-7 验收组1:红冲连锁(审计 P0-1 / 穿行场景10)。
 * 覆盖:红冲后库存归零 / 有下游拒绝 / 红冲免负库存拦截 / 影响清单数字正确 / PO 已收量回冲。
 */
@ActiveProfiles("test-period")
class RedFlushChainTest extends BaseIntegrationTest {

    private static final Long P = 701L;
    private static final Long M = 801L;

    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private StockLedgerMapper ledgerMapper;
    @Autowired
    private PurchaseOrderService poService;
    @Autowired
    private PurchaseReceiptService receiptService;
    @Autowired
    private PurchaseOrderItemMapper poItemMapper;

    @Test
    @DisplayName("红冲后库存归零:采购入库10→红冲→仓库0;反向流水挂红冲单;原单已红冲")
    void redFlushZeroesStock() {
        Long originId = stockWarehouse(P, "10");
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("10")));

        Long redId = redFlushService.execute(originId, OP, "数量录错整单红冲", false);

        assertEquals(0, stockService.getWarehouseStock(P).compareTo(BigDecimal.ZERO));
        DocHead origin = docHeadMapper.selectById(originId);
        assertEquals(DocStatus.RED_FLUSHED, origin.getDocStatus());
        DocHead red = docHeadMapper.selectById(redId);
        assertEquals(DocType.RED_FLUSH, red.getDocType());
        assertEquals(DocStatus.CONFIRMED, red.getDocStatus());
        assertEquals(originId, red.getRedFlushOf());
        assertTrue(Boolean.TRUE.equals(red.getNegStockExempt()), "红冲单默认免负库存拦截");
        List<StockLedger> rows = ledgerMapper.selectList(
                new LambdaQueryWrapper<StockLedger>().eq(StockLedger::getDocId, redId));
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).getChangeQty().compareTo(new BigDecimal("-10")), "反向流水 = −10");
    }

    @Test
    @DisplayName("红冲免负库存拦截:仓库只剩2仍可红冲整单10 → 仓库−8 放行(手工单同量出库会被拦)")
    void redFlushExemptFromNegStockIntercept() {
        Long originId = stockWarehouse(P, "10");
        confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{P, "8", "3.5"});
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("2")));

        // 对照:手工报损10会被负库存拦截
        assertThrows(BizException.class, () -> confirmedDoc(DocType.DAMAGE, null,
                DocService.SOURCE_MANUAL, LocalDate.now(), false, null, new Object[]{P, "10", "3.5"}));

        // 红冲同量10:免拦截放行,仓库 −8
        redFlushService.execute(originId, OP, "供应商整单退回", false);
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("-8")));
    }

    @Test
    @DisplayName("有下游拒绝:①被预挂单冲抵关联的导入单 ②已红冲过的单,均列出原因拒绝")
    void redFlushRejectedWhenDownstreamExists() {
        // ① 导入转移单冲抵了手工预挂单 → 该导入单有下游动作
        stockWarehouse(P, "50");
        Long importDocId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{P, "10", "3.5"});
        Long manualId = docService.createDoc(req(DocType.TRANSFER_OUT, M, DocService.SOURCE_MANUAL,
                LocalDate.now().plusDays(1), new Object[]{P, "10", "3.5"}), OP);
        // 手工单确认进预挂单(错开日期避开碰撞检查,再改回同日匹配窗口)
        docService.submit(manualId, OP);
        docService.confirm(manualId, OP, false, null);
        DocHead manual = docHeadMapper.selectById(manualId);
        manual.setBizDate(LocalDate.now());
        docHeadMapper.updateById(manual);
        List<Long> matched = stockService.matchPendingTransfer(importDocId, OP);
        assertEquals(Collections.singletonList(manualId), matched);

        RedFlushService.Preview p = redFlushService.preview(importDocId);
        assertFalse(p.isExecutable());
        assertTrue(p.getBlockers().stream().anyMatch(b -> b.contains("预挂单")), "拦截原因列出被冲抵预挂单");
        assertThrows(BizException.class, () -> redFlushService.execute(importDocId, OP, "试红冲", false));

        // ② 已红冲过的单再红冲 → 状态已红冲被拒
        Long originId = stockWarehouse(P, "10");
        redFlushService.execute(originId, OP, "第一次红冲", false);
        BizException e = assertThrows(BizException.class,
                () -> redFlushService.execute(originId, OP, "重复红冲", false));
        assertTrue(e.getMessage().contains("已确认") || e.getMessage().contains("已红冲"));
    }

    @Test
    @DisplayName("影响清单数字正确:出库上架红冲=仓库+30机器−30;采购红冲=仓库−100,毛利影响=−350")
    void previewNumbersCorrect() {
        Long purchaseId = stockWarehouse(P, "100"); // 100 × 3.5 = 350
        Long transferId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{P, "30", "3.5"});

        RedFlushService.Preview pt = redFlushService.preview(transferId);
        assertTrue(pt.isExecutable());
        Map<String, Object> row = pt.getStockChanges().get(0);
        assertEquals(0, ((BigDecimal) row.get("warehouseDelta")).compareTo(new BigDecimal("30")), "转移红冲仓库+30");
        assertEquals(0, ((BigDecimal) row.get("machineDelta")).compareTo(new BigDecimal("-30")), "转移红冲机器−30");

        RedFlushService.Preview pp = redFlushService.preview(purchaseId);
        Map<String, Object> prow = pp.getStockChanges().get(0);
        assertEquals(0, ((BigDecimal) prow.get("warehouseDelta")).compareTo(new BigDecimal("-100")));
        assertEquals(0, ((BigDecimal) prow.get("warehouseNow")).compareTo(new BigDecimal("70")));
        assertEquals(0, ((BigDecimal) prow.get("warehouseAfter")).compareTo(new BigDecimal("-30")));
        BigDecimal reversal = (BigDecimal) pp.getMarginImpacts().get(0).get("costAmountReversal");
        assertEquals(0, reversal.compareTo(new BigDecimal("-350")), "该月存货成本金额反向 −350");
    }

    @Test
    @DisplayName("红冲连锁回冲 PO 已收量:订货→收货确认(已收=在途0)→红冲→已收归零在途恢复")
    void redFlushReversesPoReceivedQty() {
        PoCreateReq poReq = new PoCreateReq();
        poReq.setSupplierId(601L);
        PoCreateReq.Item item = new PoCreateReq.Item();
        item.setProductId(P);
        item.setQtyOrdered(new BigDecimal("20"));
        item.setUnitPrice(new BigDecimal("3.5"));
        poReq.setItems(Collections.singletonList(item));
        Long poId = poService.create(poReq, "测试员");
        poService.place(poId, "测试员");
        Long receiptId = receiptService.createFromPo(poId, LocalDate.now(), OP);
        receiptService.confirm(receiptId, OP);
        assertEquals(0, poService.inTransit(P).compareTo(BigDecimal.ZERO), "全收后在途0");

        redFlushService.execute(receiptId, OP, "整单数量录错", false);

        BigDecimal received = poItemMapper.selectList(new LambdaQueryWrapper<top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem>()
                        .eq(top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem::getPoId, poId))
                .get(0).getQtyReceived();
        assertEquals(0, received.compareTo(BigDecimal.ZERO), "红冲回冲已收量归零");
        assertEquals(0, poService.inTransit(P).compareTo(new BigDecimal("20")), "在途恢复20");
    }
}
