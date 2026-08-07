package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.CostAdjustReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.mapper.DocItemMapper;
import top.aole.vend.modules.doc.service.CostAdjustService;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.doc.service.RedFlushService;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.mapper.PurchaseOrderItemMapper;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.purchase.service.PurchaseReceiptService;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.mapper.PaymentMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;
import top.aole.vend.modules.settle.service.PaymentService;
import top.aole.vend.modules.settle.service.SettleBillService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景10:红冲连锁(审计结论:断——已售货/加权成本/已付款三条连锁全空白,红冲还会被负库存拦截卡死;
 * P0-1 修复:数量错→整单红冲(限无下游);单价错→成本调整单(qty=0/amount=Δ);已付款→应付红字(M3);
 * 红冲免负库存拦截 + 强制影响清单确认页)。
 */
class Scenario10RedFlushChainTest extends RegressionSupport {

    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private CostAdjustService costAdjustService;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private DocItemMapper docItemMapper;
    @Autowired
    private StockLedgerMapper ledgerMapper;
    @Autowired
    private PurchaseOrderService poService;
    @Autowired
    private PurchaseReceiptService receiptService;
    @Autowired
    private PurchaseOrderItemMapper poItemMapper;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private SettleBillMapper billMapper;
    @Autowired
    private PaymentMapper paymentMapper;
    @Autowired
    private SettleBillService settleBillService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private AttachmentService attachmentService;

    @Test
    @DisplayName("数量错→整单红冲:反向单免拦截过账,原单→已红冲,库存归零;红冲单/调整单不许再红冲")
    void quantityErrorFullRedFlush() {
        Product p = product("录错数量的可乐", null, null);
        Long originId = stockWarehouse(p.getId(), "10");
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("10")));

        Long redId = redFlushService.execute(originId, OP, "数量录错整单红冲", false);
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(BigDecimal.ZERO));
        assertEquals(DocStatus.RED_FLUSHED, docHeadMapper.selectById(originId).getDocStatus());

        DocHead red = docHeadMapper.selectById(redId);
        assertEquals(DocType.RED_FLUSH, red.getDocType());
        assertEquals(originId, red.getRedFlushOf());
        assertTrue(Boolean.TRUE.equals(red.getNegStockExempt()));
        List<StockLedger> rows = ledgerMapper.selectList(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getDocId, redId));
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).getChangeQty().compareTo(new BigDecimal("-10")), "按原单方向反向过账");

        // 红冲单不许再红冲(连锁止损)
        BizException e = assertThrows(BizException.class,
                () -> redFlushService.execute(redId, OP, "套娃", false));
        assertTrue(e.getMessage().contains("不允许再红冲"));
    }

    @Test
    @DisplayName("免负库存拦截(修卡死):货已上架仓库只剩2,红冲整单10 → −8放行亮账;对照手工报损被拦")
    void redFlushExemptFromNegStock() {
        Machine m = machine("红冲场景机");
        Product p = product("已上架的红牛", null, null);
        Long originId = stockWarehouse(p.getId(), "10");
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{p.getId(), "8", "3.5"});
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("2")));

        assertThrows(BizException.class, () -> confirmedDoc(DocType.DAMAGE, null,
                DocService.SOURCE_MANUAL, LocalDate.now(), false, null, new Object[]{p.getId(), "10", "3.5"}),
                "对照:手工出库同量被负库存拦截");

        redFlushService.execute(originId, OP, "供应商整单退回", false);
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("-8")),
                "红冲免拦截,负库存如实亮账(修\"红冲被拦截卡死\")");
    }

    @Test
    @DisplayName("下游拒绝+影响清单:被预挂单冲抵的导入单不可红冲(blockers 列明);影响清单数字正确")
    void downstreamBlockAndPreviewNumbers() {
        Machine m = machine("红冲下游机");
        Product p = product("有下游的雪碧", null, null);
        Long purchaseId = stockWarehouse(p.getId(), "100"); // 100×3.5=350
        Long importDocId = confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{p.getId(), "30", "3.5"});

        // 影响清单:转移单红冲=仓库+30/机器−30;采购红冲=仓库−100、毛利影响−350
        RedFlushService.Preview pt = redFlushService.preview(importDocId);
        assertTrue(pt.isExecutable());
        Map<String, Object> row = pt.getStockChanges().get(0);
        assertEquals(0, ((BigDecimal) row.get("warehouseDelta")).compareTo(new BigDecimal("30")));
        assertEquals(0, ((BigDecimal) row.get("machineDelta")).compareTo(new BigDecimal("-30")));

        RedFlushService.Preview pp = redFlushService.preview(purchaseId);
        Map<String, Object> prow = pp.getStockChanges().get(0);
        assertEquals(0, ((BigDecimal) prow.get("warehouseDelta")).compareTo(new BigDecimal("-100")));
        assertEquals(0, ((BigDecimal) prow.get("warehouseNow")).compareTo(new BigDecimal("70")));
        assertEquals(0, ((BigDecimal) prow.get("warehouseAfter")).compareTo(new BigDecimal("-30")));
        assertEquals(0, ((BigDecimal) pp.getMarginImpacts().get(0).get("costAmountReversal"))
                .compareTo(new BigDecimal("-350")));

        // 造下游:手工预挂单被该导入单冲抵 → 导入单红冲被拒且列明原因
        Long manualId = docService.createDoc(req(DocType.TRANSFER_OUT, m.getId(),
                DocService.SOURCE_MANUAL, LocalDate.now().plusDays(1), new Object[]{p.getId(), "30", "3.5"}), OP);
        docService.submit(manualId, OP);
        docService.confirm(manualId, OP, false, null);
        DocHead manual = docHeadMapper.selectById(manualId);
        manual.setBizDate(LocalDate.now());
        docHeadMapper.updateById(manual);
        assertEquals(Collections.singletonList(manualId),
                stockService.matchPendingTransfer(importDocId, OP));

        RedFlushService.Preview blockedPreview = redFlushService.preview(importDocId);
        assertFalse(blockedPreview.isExecutable());
        assertTrue(blockedPreview.getBlockers().stream().anyMatch(b -> b.contains("预挂单")),
                "拦截原因列出被冲抵预挂单");
        assertThrows(BizException.class,
                () -> redFlushService.execute(importDocId, OP, "试红冲", false));
    }

    @Test
    @DisplayName("红冲必填原因(影响清单确认页强制备注):空原因直接拒绝")
    void redFlushRequiresReason() {
        Product p = product("没写原因的泡面", null, null);
        Long originId = stockWarehouse(p.getId(), "5");
        assertThrows(BizException.class, () -> redFlushService.execute(originId, OP, "  ", false));
        assertEquals(DocStatus.CONFIRMED, docHeadMapper.selectById(originId).getDocStatus(), "原单原样");
    }

    @Test
    @DisplayName("单价错→成本调整单(附录C):已售1不追溯,未售9调存货 qty=0/amount=+4.5,库存数量不动")
    void priceErrorCostAdjust() {
        Machine m = machine("成本调整机");
        Product p = product("单价录错的槟榔", null, null);
        Long originId = stockWarehouse(p.getId(), "10"); // 10@3.5,实价应为4.0
        confirmedDoc(DocType.TRANSFER_OUT, m.getId(), DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{p.getId(), "4", "3.5"});
        sale(m.getId(), p.getId(), "1", "6.0", "正常", LocalDate.now().atTime(12, 0));

        Long itemId = docItemMapper.selectList(new LambdaQueryWrapper<DocItem>()
                .eq(DocItem::getDocId, originId)).get(0).getId();
        CostAdjustReq req = new CostAdjustReq();
        CostAdjustReq.Line line = new CostAdjustReq.Line();
        line.setDocItemId(itemId);
        line.setCorrectPrice(new BigDecimal("4.0"));
        req.setLines(Collections.singletonList(line));

        CostAdjustService.Preview preview = costAdjustService.preview(originId, req);
        assertEquals(0, preview.getUnsoldAdjustTotal().compareTo(new BigDecimal("4.5")), "未售9×0.5");
        assertEquals(0, preview.getSoldAdjustTotal().compareTo(new BigDecimal("0.5")), "已售1×0.5不追溯");

        Long adjustId = costAdjustService.execute(originId, req, OP);
        DocHead adjust = docHeadMapper.selectById(adjustId);
        assertEquals(DocType.COST_ADJUST, adjust.getDocType());
        assertEquals(originId, adjust.getRedFlushOf(), "调整单锚定原采购单");
        assertEquals("成本调整", adjust.getPlLine(), "已售部分→利润表\"成本调整\"行标记(M3 取数)");

        List<StockLedger> rows = ledgerMapper.selectList(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getDocId, adjustId));
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).getChangeQty().compareTo(BigDecimal.ZERO), "qty=0 只动金额");
        assertEquals(0, rows.get(0).getAmount().compareTo(new BigDecimal("4.5")));
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("6")),
                "库存数量不因成本调整变动");
    }

    @Test
    @DisplayName("应付链通道已开通(M3-2):采购单进待结算后红冲不再被拦,连锁执行且原单→已红冲")
    void paidChainOpenedByM32() {
        Product p = product("已进结算链的货", null, null);
        Long originId = stockWarehouse(p.getId(), "10");
        // 采购单进入应付结算链(M3 状态通道:已确认→待结算)
        DocHead head = docHeadMapper.selectById(originId);
        head.setDocStatus(DocStatus.PENDING_SETTLE);
        docHeadMapper.updateById(head);

        RedFlushService.Preview preview = redFlushService.preview(originId);
        assertTrue(preview.isExecutable(), "M1 时代的'M3 开通'拦截已被红冲+应付连锁事务取代");

        redFlushService.execute(originId, OP, "待结算单据整单红冲", false);
        assertEquals(DocStatus.RED_FLUSHED, docHeadMapper.selectById(originId).getDocStatus(),
                "待结算→已红冲(状态机 M3-2 新通道);无结算单(无供应商垫底单)则无应付连锁");
    }

    @Test
    @DisplayName("红冲连锁回冲 PO:订货20→全收→红冲收货单→已收归零、在途恢复20(在途账不断链)")
    void redFlushReversesPoReceived() {
        Product p = product("回冲在途的货", null, null);
        PoCreateReq poReq = new PoCreateReq();
        poReq.setSupplierId(601L);
        PoCreateReq.Item item = new PoCreateReq.Item();
        item.setProductId(p.getId());
        item.setQtyOrdered(new BigDecimal("20"));
        item.setUnitPrice(new BigDecimal("3.5"));
        poReq.setItems(Collections.singletonList(item));
        Long poId = poService.create(poReq, OPERATOR);
        poService.place(poId, OPERATOR);
        Long receiptId = receiptService.createFromPo(poId, LocalDate.now(), OP);
        receiptService.confirm(receiptId, OP);
        assertEquals(0, poService.inTransit(p.getId()).compareTo(BigDecimal.ZERO));

        redFlushService.execute(receiptId, OP, "整单数量录错", false);
        BigDecimal received = poItemMapper.selectList(
                        new LambdaQueryWrapper<top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem>()
                                .eq(top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem::getPoId, poId))
                .get(0).getQtyReceived();
        assertEquals(0, received.compareTo(BigDecimal.ZERO), "已收量回冲归零");
        assertEquals(0, poService.inTransit(p.getId()).compareTo(new BigDecimal("20")), "在途恢复");
    }

    @Test
    @DisplayName("已付款红冲连锁(M3-2 转绿):红冲→生成 settle_bill(direction=红字)待冲抵;下一单老板确认自动抵减;不动已完成付款")
    void paidRedFlushGeneratesRedSettleBill() {
        // 造全链:供应商→采购确认(自动生成结算单)→老板复核→付款(传凭证)→核销闭环
        top.aole.vend.modules.basedata.domain.entity.Supplier s = new top.aole.vend.modules.basedata.domain.entity.Supplier();
        s.setSupplierCode("RGS" + SEQ.incrementAndGet());
        s.setSupplierName("红字连锁供应商");
        s.setSettleMethod("现结");
        s.setAccountDays(0);
        s.setOpeningPayable(java.math.BigDecimal.ZERO);
        s.setCoopStatus("合作中");
        supplierMapper.insert(s);
        top.aole.vend.modules.money.domain.entity.Account a = new top.aole.vend.modules.money.domain.entity.Account();
        a.setAccountName("RG微信" + SEQ.incrementAndGet());
        a.setAccountType("微信");
        a.setIsVirtual(false);
        a.setOpeningBalance(new BigDecimal("1000"));
        accountMapper.insert(a);

        Product p = product("已付款要红冲的货", null, null);
        top.aole.vend.modules.doc.dto.DocCreateReq req = req(DocType.PURCHASE_IN, null,
                DocService.SOURCE_MANUAL, LocalDate.now(), new Object[]{p.getId(), "10", "3.5"}); // 35
        req.setSupplierId(s.getId());
        Long originId = docService.createDoc(req, OP);
        docService.submit(originId, OP);
        docService.confirm(originId, OP, false, null);

        SettleBill bill = billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSourceDocId, originId).eq(SettleBill::getDirection, "正常"));
        assertNotNull(bill, "采购确认自动生成结算单");
        settleBillService.confirm(bill.getId(), null, OP, "回归测试员", "老板");

        top.aole.vend.modules.settle.dto.SettleDtos.PaymentCreateReq payReq =
                new top.aole.vend.modules.settle.dto.SettleDtos.PaymentCreateReq();
        payReq.setSupplierId(s.getId());
        payReq.setAccountId(a.getId());
        payReq.setAmount(new BigDecimal("35"));
        payReq.setSettleBillId(bill.getId());
        Long payId = paymentService.create(payReq, OP, "回归测试员");
        attachmentService.upload("payment", payId, "转账截图", "转账.png",
                "png".getBytes(java.nio.charset.StandardCharsets.UTF_8), OP, "回归测试员");
        paymentService.confirm(payId, OP, "回归测试员");
        assertEquals(DocStatus.SETTLED, docHeadMapper.selectById(originId).getDocStatus(), "全链闭环:已结算");

        // 红冲已付款采购单 → 应付红字连锁(§13.2-1)
        redFlushService.execute(originId, OP, "已付款后发现整单错", false);
        assertEquals("结算完成", paymentMapper.selectById(payId).getPayStatus(), "不动已完成付款");
        SettleBill red = billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSupplierId, s.getId()).eq(SettleBill::getDirection, "红字"));
        assertNotNull(red, "生成应付红字");
        assertEquals("待冲抵", red.getBillStatus());
        assertEquals(0, red.getAmountDue().compareTo(new BigDecimal("35")), "红字金额=已付款额");

        // 下一单 70:老板确认自动抵减红字 → 实结 35
        top.aole.vend.modules.doc.dto.DocCreateReq req2 = req(DocType.PURCHASE_IN, null,
                DocService.SOURCE_MANUAL, LocalDate.now(), new Object[]{p.getId(), "20", "3.5"});
        req2.setSupplierId(s.getId());
        Long doc2 = docService.createDoc(req2, OP);
        docService.submit(doc2, OP);
        docService.confirm(doc2, OP, false, null);
        SettleBill bill2 = billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSourceDocId, doc2).eq(SettleBill::getDirection, "正常"));
        top.aole.vend.modules.settle.service.SettleBillService.ConfirmResult result =
                settleBillService.confirm(bill2.getId(), null, OP, "回归测试员", "老板");
        assertEquals(0, result.getRedOffsetAmount().compareTo(new BigDecimal("35")), "红字冲抵下一单");
        assertEquals(0, result.getAmountActual().compareTo(new BigDecimal("35")), "70−35");
        assertEquals("已冲抵", billMapper.selectById(red.getId()).getBillStatus());
    }
}
