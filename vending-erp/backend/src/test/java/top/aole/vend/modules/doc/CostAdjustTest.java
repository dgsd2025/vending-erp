package top.aole.vend.modules.doc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.CostAdjustReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.mapper.DocItemMapper;
import top.aole.vend.modules.doc.service.CostAdjustService;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-7 验收组2:成本调整单(P0-1 单价错 · 附录C 过账契约)。
 * 覆盖:qty=0/amount=Δ 落 ledger / 未售已售切分 / 已售进备注+pl_line / 非采购单拒绝。
 */
@ActiveProfiles("test-period")
class CostAdjustTest extends BaseIntegrationTest {

    private static final Long P = 702L;
    private static final Long M = 802L;

    @Autowired
    private CostAdjustService costAdjustService;
    @Autowired
    private PeriodLockService periodLockService;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private DocItemMapper docItemMapper;
    @Autowired
    private StockLedgerMapper ledgerMapper;

    private CostAdjustReq reqOf(Long docItemId, String correctPrice) {
        CostAdjustReq req = new CostAdjustReq();
        CostAdjustReq.Line line = new CostAdjustReq.Line();
        line.setDocItemId(docItemId);
        line.setCorrectPrice(new BigDecimal(correctPrice));
        req.setLines(Collections.singletonList(line));
        return req;
    }

    private Long itemIdOf(Long docId) {
        return docItemMapper.selectList(new LambdaQueryWrapper<DocItem>()
                .eq(DocItem::getDocId, docId)).get(0).getId();
    }

    @Test
    @DisplayName("附录C:采购10@3.5实价4.0,已售1 → 未售9调存货+4.50(qty=0行落ledger),已售0.50进备注+pl_line")
    void costAdjustPostsZeroQtyLedgerRow() {
        Long originId = stockWarehouse(P, "10"); // 10 × 3.5
        confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{P, "4", "3.5"});
        insertSale(M, P, "1", "正常", LocalDateTime.now());

        CostAdjustService.Preview p = costAdjustService.preview(originId, reqOf(itemIdOf(originId), "4.0"));
        Map<String, Object> line = p.getLines().get(0);
        assertEquals(0, ((BigDecimal) line.get("soldQty")).compareTo(BigDecimal.ONE), "已售=1(正常口径)");
        assertEquals(0, ((BigDecimal) line.get("unsoldQty")).compareTo(new BigDecimal("9")));
        assertEquals(0, p.getUnsoldAdjustTotal().compareTo(new BigDecimal("4.5")), "未售Δ=9×0.5");
        assertEquals(0, p.getSoldAdjustTotal().compareTo(new BigDecimal("0.5")), "已售Δ=1×0.5");

        Long adjustId = costAdjustService.execute(originId, reqOf(itemIdOf(originId), "4.0"), OP);
        DocHead adjust = docHeadMapper.selectById(adjustId);
        assertEquals(DocType.COST_ADJUST, adjust.getDocType());
        assertEquals(DocStatus.CONFIRMED, adjust.getDocStatus());
        assertEquals(originId, adjust.getRedFlushOf(), "成本调整锚定原采购入库单");
        assertEquals("成本调整", adjust.getPlLine(), "已售部分预留利润表行标记");
        assertTrue(adjust.getRemark().contains("已售不追溯 +0.50"), "已售金额记入单据备注:" + adjust.getRemark());

        List<StockLedger> rows = ledgerMapper.selectList(
                new LambdaQueryWrapper<StockLedger>().eq(StockLedger::getDocId, adjustId));
        assertEquals(1, rows.size(), "成本调整落一条 ledger 调整流水");
        assertEquals(0, rows.get(0).getChangeQty().compareTo(BigDecimal.ZERO), "qty=0 只动金额(附录C)");
        assertEquals(0, rows.get(0).getAmount().compareTo(new BigDecimal("4.5")), "amount=Δ=+4.50");
        assertNull(rows.get(0).getUnitCost(), "qty=0 行无单价语义");
        // 库存数量不变(只调金额)
        assertEquals(0, stockService.getWarehouseStock(P).compareTo(new BigDecimal("6")));
    }

    @Test
    @DisplayName("全部未售:无销量时 已售=0、pl_line 不打标、ledger 金额=全量Δ")
    void costAdjustAllUnsold() {
        Long originId = stockWarehouse(P, "10");
        Long adjustId = costAdjustService.execute(originId, reqOf(itemIdOf(originId), "3.0"), OP); // 降价 Δ=-0.5
        DocHead adjust = docHeadMapper.selectById(adjustId);
        assertNull(adjust.getPlLine(), "无已售部分不打利润表行标记");
        List<StockLedger> rows = ledgerMapper.selectList(
                new LambdaQueryWrapper<StockLedger>().eq(StockLedger::getDocId, adjustId));
        assertEquals(0, rows.get(0).getAmount().compareTo(new BigDecimal("-5")), "10×(3.0−3.5)=−5 调存货");
    }

    @Test
    @DisplayName("七律审计:锁账期原单成本调整被拒;bossOverride 需老板角色头+备注才放行(与红冲同一把尺子)")
    void lockedPeriodCostAdjustNeedsBossOverrideWithRole() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        String lastMonth = YearMonth.now().minusMonths(1).format(fmt);
        // 上月确认一张采购入库(锁之前确认 → book_period=上月)
        Long originId = confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                YearMonth.now().minusMonths(1).atDay(10), false, null, new Object[]{P, "10", "3.5"});
        assertEquals(lastMonth, docHeadMapper.selectById(originId).getBookPeriod());
        periodLockService.lock(lastMonth, OP, "月报已出");

        // ① 普通调用 → 拒绝(带锁账提示)
        BizException e1 = assertThrows(BizException.class,
                () -> costAdjustService.execute(originId, reqOf(itemIdOf(originId), "4.0"), OP));
        assertTrue(e1.getMessage().contains("已锁账"), e1.getMessage());

        // ② bossOverride=true 但角色头不是老板 → 拒绝(P1-2 同逻辑:不许光传布尔就放行)
        CostAdjustReq boss = reqOf(itemIdOf(originId), "4.0");
        boss.setBossOverride(true);
        boss.setRemark("老板拍板:上月进价录错");
        assertThrows(BizException.class,
                () -> costAdjustService.execute(originId, boss, OP, "录单员"),
                "非老板角色不可越权");

        // ③ bossOverride 但备注为空 → 拒绝(越权强制备注)
        CostAdjustReq noRemark = reqOf(itemIdOf(originId), "4.0");
        noRemark.setBossOverride(true);
        assertThrows(BizException.class,
                () -> costAdjustService.execute(originId, noRemark, OP, PeriodLockService.ROLE_BOSS),
                "越权必须强制备注");

        // ④ 老板角色 + bossOverride + 备注 → 放行;调整单落当月(旧报表永不重算)
        Long adjustId = costAdjustService.execute(originId, boss, OP, PeriodLockService.ROLE_BOSS);
        DocHead adjust = docHeadMapper.selectById(adjustId);
        assertEquals(DocStatus.CONFIRMED, adjust.getDocStatus());
        assertEquals(YearMonth.now().format(fmt), adjust.getBookPeriod(), "锁账期成本调整单入当月");
    }

    @Test
    @DisplayName("守卫:非采购入库单拒绝;单价相同拒绝;不属于原单的明细行拒绝")
    void costAdjustGuards() {
        stockWarehouse(P, "10");
        Long transferId = confirmedDoc(DocType.TRANSFER_OUT, M, DocService.SOURCE_IMPORT,
                LocalDate.now(), false, null, new Object[]{P, "2", "3.5"});
        assertThrows(BizException.class,
                () -> costAdjustService.preview(transferId, reqOf(itemIdOf(transferId), "4.0")),
                "成本调整仅针对采购入库单");

        Long originId = stockWarehouse(P, "5");
        assertThrows(BizException.class,
                () -> costAdjustService.preview(originId, reqOf(itemIdOf(originId), "3.5")),
                "正确单价与原单价相同应拒绝");
        assertThrows(BizException.class,
                () -> costAdjustService.preview(originId, reqOf(999999L, "4.0")),
                "明细行不属于原单应拒绝");
    }
}
