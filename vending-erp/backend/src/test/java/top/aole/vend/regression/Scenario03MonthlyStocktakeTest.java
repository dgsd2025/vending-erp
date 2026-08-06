package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.mapper.StocktakeMapper;
import top.aole.vend.modules.stocktake.money.domain.entity.CashCheckItem;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos;
import top.aole.vend.modules.stocktake.money.service.CashAdjustService;
import top.aole.vend.modules.stocktake.money.service.CashCheckService;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景3:月度盘点日(审计结论:断——钱盘发现账户差异后无单据可落地;P1-7 资金调整单修复)。
 *
 * M1 已落地:doc_type=资金调整 十枚举在列,状态机通道全通(草稿→待确认→已确认→已完成),
 *   确认不动库存(postingDeferred,钱账 M3);盘盈/盘亏单通道与过账已通(货盘出口)。
 * M3-5 已接通(两条 Disabled 转绿):资金调整确认 → cash_flow(pl_line=不进利润表)+余额修正;
 *   应付核对不符 → 补录(跳付款/抵扣录入)或 红冲(跳对应单据)两出口。
 */
class Scenario03MonthlyStocktakeTest extends RegressionSupport {

    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private StockLedgerMapper ledgerMapper;
    @Autowired
    private StocktakeService stocktakeService;
    @Autowired
    private StocktakeMapper stocktakeMapper;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CashAdjustService cashAdjustService;
    @Autowired
    private CashCheckService cashCheckService;
    @Autowired
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private SupplierMapper supplierMapper;

    @Test
    @DisplayName("资金调整单(P1-7 钱盘差异唯一出口):建单→提交→确认→完成 状态机全通;确认不写库存流水")
    void cashAdjustDocStateMachineChannelOpen() {
        Product p = product("钱盘差异占位品", null, null);
        Long docId = docService.createDoc(req(DocType.CASH_ADJUST, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), new Object[]{p.getId(), "1", "13.20"}), OP);
        DocHead head = docHeadMapper.selectById(docId);
        assertEquals(DocType.CASH_ADJUST, head.getDocType());
        assertTrue(head.getDocNo().startsWith("ZJ-"), "资金调整单号前缀 ZJ:" + head.getDocNo());

        docService.submit(docId, OP);
        docService.confirm(docId, OP, false, null);
        assertEquals(DocStatus.CONFIRMED, docHeadMapper.selectById(docId).getDocStatus());

        // postingDeferred:确认后不产生任何库存流水(资金调整只动钱,M3 生成 cash_flow)
        assertEquals(0, ledgerMapper.selectCount(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getDocId, docId)), "资金调整不碰库存账");

        docService.complete(docId, OP);
        assertEquals(DocStatus.COMPLETED, docHeadMapper.selectById(docId).getDocStatus(), "通道走到已完成");
    }

    @Test
    @DisplayName("货盘出口已通:盘盈入库+3/盘亏出库−2 过账正确(月盘的货侧闭环)")
    void stocktakeGainLossDocsPost() {
        Product p = product("月盘可乐", null, null);
        stockWarehouse(p.getId(), "10");
        confirmedDoc(DocType.GAIN_IN, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{p.getId(), "3", "2.0"});
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("13")));
        confirmedDoc(DocType.LOSS_OUT, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{p.getId(), "2", "2.0"});
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("11")));
    }

    @Test
    @DisplayName("钱账落地通道字段在位:cash_flow.pl_line 含资金调整去处;settle_bill.direction 红字通道;账户期初锁字段")
    void moneySideSchemaChannelsExist() {
        Map<String, Object> plLine = assertColumn("yc_vend_cash_flow", "pl_line");
        assertTrue(String.valueOf(plLine.get("COLUMN_COMMENT")).contains("资金调整"),
                "pl_line 注释写死资金调整去处(每类流水在利润表有且只有一个去处)");
        assertColumn("yc_vend_cash_flow", "ref_doc_type");
        assertColumn("yc_vend_cash_flow", "ref_doc_id"); // 铁律:钱不许直接记一笔,流水必挂单据

        Map<String, Object> direction = assertColumn("yc_vend_settle_bill", "direction");
        assertEquals("正常", String.valueOf(direction.get("COLUMN_DEFAULT")), "应付方向默认正常,红字备用(P0-1)");

        // 钱盘核对基础:账户期初只能设一次(opening_set_at 非空即锁定)
        assertColumn("yc_vend_account", "opening_set_at");
        assertColumn("yc_vend_account", "is_virtual"); // 平台待结算/老板垫付不参与实数核对
        // 盘点两表在位(M2/M3 盘点服务的落点)
        assertTableExists("yc_vend_stocktake");
        assertTableExists("yc_vend_stocktake_item");
        assertColumn("yc_vend_stocktake_item", "diff_reason");
    }

    @Test
    @DisplayName("M2 升级:月盘货侧闭环端到端——盘点单驱动 盘盈+2/盘亏−2 自动过账,库存=实盘,差异按加权成本计价")
    void monthlyStocktakeEndToEndViaService() {
        Product a = product("月盘盘盈品", null, null);
        Product b = product("月盘盘亏品", null, null);
        stockWarehouse(a.getId(), "10");
        stockWarehouse(b.getId(), "5");

        // 创建(系统快照账面)→ 录差异(整包)→ 提交 → 确认(自动生成盘盈/盘亏单并过账)
        StocktakeDtos.CreateReq createReq = new StocktakeDtos.CreateReq();
        createReq.setScopeType(Stocktake.SCOPE_WAREHOUSE);
        createReq.setSourceTask("月度盘点");
        Long stId = stocktakeService.create(createReq, OP);

        StocktakeDtos.SaveItemsReq saveReq = new StocktakeDtos.SaveItemsReq();
        StocktakeDtos.ItemReq r1 = new StocktakeDtos.ItemReq();
        r1.setProductId(a.getId());
        r1.setActualQty(new BigDecimal("12"));
        r1.setDiffReason(StocktakeItem.REASON_COUNT_ERROR);
        StocktakeDtos.ItemReq r2 = new StocktakeDtos.ItemReq();
        r2.setProductId(b.getId());
        r2.setActualQty(new BigDecimal("3"));
        r2.setDiffReason(StocktakeItem.REASON_EXPIRE);
        saveReq.getRows().add(r1);
        saveReq.getRows().add(r2);
        stocktakeService.saveItems(stId, saveReq, OP);
        stocktakeService.submit(stId, OP);
        StocktakeDtos.ConfirmResp resp = stocktakeService.confirm(stId, OP, "员工", false, null);

        // 盘盈/盘亏两单自动生成并已过账
        assertNotNull(resp.getGainDocId(), "盘盈单自动生成");
        assertNotNull(resp.getLossDocId(), "盘亏单自动生成");
        assertEquals(DocType.GAIN_IN, docHeadMapper.selectById(resp.getGainDocId()).getDocType());
        assertEquals(DocType.LOSS_OUT, docHeadMapper.selectById(resp.getLossDocId()).getDocType());
        assertEquals(DocStatus.CONFIRMED, docHeadMapper.selectById(resp.getGainDocId()).getDocStatus());
        assertEquals(DocStatus.CONFIRMED, docHeadMapper.selectById(resp.getLossDocId()).getDocStatus());
        // 库存=实盘
        assertEquals(0, stockService.getWarehouseStock(a.getId()).compareTo(new BigDecimal("12")),
                "盘盈过账:仓库账修正到实盘 12");
        assertEquals(0, stockService.getWarehouseStock(b.getId()).compareTo(new BigDecimal("3")),
                "盘亏过账:仓库账修正到实盘 3");
        // 差异按加权成本计价(3.5):盘盈 +7 / 盘亏 −7
        assertEquals(0, resp.getGainAmount().compareTo(new BigDecimal("7.00")), "盘盈金额=+2×3.5");
        assertEquals(0, resp.getLossAmount().compareTo(new BigDecimal("-7.00")), "盘亏金额=−2×3.5");
        // 盘点单终态 + 双单挂接(月盘留档)
        Stocktake st = stocktakeMapper.selectById(stId);
        assertEquals(Stocktake.ST_DONE, st.getStStatus());
        assertEquals(resp.getGainDocId(), st.getGainDocId());
        assertEquals(resp.getLossDocId(), st.getLossDocId());
    }

    @Test
    @DisplayName("M3-5 转绿:钱盘差异 −13.2 → 资金调整单老板确认 → cash_flow 一条(不进利润表)→ 余额=期初+Σ流水吻合")
    void cashAdjustGeneratesCashFlow() {
        MoneyDtos.AccountCreateReq accReq = new MoneyDtos.AccountCreateReq();
        accReq.setAccountName("回归现金账户" + SEQ.incrementAndGet());
        accReq.setAccountType("现金");
        accReq.setOpeningBalance(new BigDecimal("200"));
        Long accId = accountService.create(accReq, OP, OPERATOR);

        CashMoneyDtos.AdjustCreateReq req = new CashMoneyDtos.AdjustCreateReq();
        req.setAccountId(accId);
        req.setAdjustAmount(new BigDecimal("-13.20")); // 钱盘发现现金实数比系统少 13.2
        req.setReason("盘亏");
        Long docId = cashAdjustService.create(req, OP, OPERATOR);
        cashAdjustService.confirm(docId, OP, "老板", OPERATOR);

        List<CashFlow> flows = cashFlowMapper.selectList(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, CashAdjustService.REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, docId));
        assertEquals(1, flows.size(), "资金调整确认 → cash_flow 恰好一条");
        assertEquals("资金调整", flows.get(0).getCategory());
        assertEquals("不进利润表", flows.get(0).getPlLine(), "资金调整只动资产不动损益");
        assertEquals(0, accountService.balanceOf(accId).compareTo(new BigDecimal("186.80")),
                "账户余额=期初200−13.2=186.8(余额=期初+Σ流水,无直改路径)");
        assertEquals(DocStatus.COMPLETED, docHeadMapper.selectById(docId).getDocStatus(),
                "调整单过账即闭环(P1-7 断点修复)");
    }

    @Test
    @DisplayName("M3-5 转绿:应付核对不符 → 两出口(补录跳付款/抵扣录入;红冲跳对应单据),留痕后钱盘才许收口")
    void payableMismatchTwoExits() {
        Supplier a = new Supplier();
        a.setSupplierCode("QPRG" + SEQ.incrementAndGet());
        a.setSupplierName("回归供应商A");
        a.setOpeningPayable(BigDecimal.ZERO);
        supplierMapper.insert(a);
        Supplier b = new Supplier();
        b.setSupplierCode("QPRG" + SEQ.incrementAndGet());
        b.setSupplierName("回归供应商B");
        b.setOpeningPayable(BigDecimal.ZERO);
        supplierMapper.insert(b);
        // 结算单链(direction 红字通道本类第三例已验;这里造正常应付,来源单据=红冲跳转目标)
        jdbc.update("INSERT INTO yc_vend_settle_bill (bill_no, bill_type, direction, supplier_id, " +
                        "source_doc_id, amount_due, amount_actual, bill_status) VALUES (?,?,?,?,?,?,?,?)",
                "JS-RG-" + SEQ.incrementAndGet(), "应付", "正常", a.getId(), 777L, "500", "500", "待付款");
        jdbc.update("INSERT INTO yc_vend_settle_bill (bill_no, bill_type, direction, supplier_id, " +
                        "source_doc_id, amount_due, amount_actual, bill_status) VALUES (?,?,?,?,?,?,?,?)",
                "JS-RG-" + SEQ.incrementAndGet(), "应付", "正常", b.getId(), 888L, "200", "200", "待付款");

        Long checkId = cashCheckService.start(OP, OPERATOR);
        CashMoneyDtos.CheckDetailResp detail = cashCheckService.detail(checkId);
        CashMoneyDtos.CheckItemRow rowA = detail.getPayableItems().stream()
                .filter(r -> r.getRefId().equals(a.getId())).findFirst().orElseThrow(AssertionError::new);
        CashMoneyDtos.CheckItemRow rowB = detail.getPayableItems().stream()
                .filter(r -> r.getRefId().equals(b.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, rowA.getSystemAmount().compareTo(new BigDecimal("500")), "系统应付=Σ正常实结");

        // 录对方账:A 少 20(漏录付款/抵扣);B 多 30(可能重复结算 → 红冲)
        CashMoneyDtos.SaveActualsReq save = new CashMoneyDtos.SaveActualsReq();
        for (Object[] pair : new Object[][]{{rowA.getId(), "480"}, {rowB.getId(), "230"}}) {
            CashMoneyDtos.ActualRow r = new CashMoneyDtos.ActualRow();
            r.setItemId((Long) pair[0]);
            r.setActualAmount(new BigDecimal((String) pair[1]));
            save.getRows().add(r);
        }
        cashCheckService.saveActuals(checkId, save, OP, OPERATOR);

        // 不符行没走出口 → 收口被拦(报错指到两按钮)
        assertTrue(assertThrows(BizException.class,
                        () -> cashCheckService.finish(checkId, OP, OPERATOR))
                .getMessage().contains("红冲"), "收口守卫指向补录/红冲两出口");

        // 出口①补录:跳付款/抵扣录入(采购页),只跳转不重造
        CashMoneyDtos.PayableExitResp backfill = cashCheckService.markPayableExit(
                checkId, rowA.getId(), CashCheckItem.EXIT_BACKFILL, OP, OPERATOR);
        assertEquals("/purchase", backfill.getRoute());
        // 出口②红冲:跳对应单据(前端打开单据抽屉走既有红冲入口 → 连锁生成应付红字)
        CashMoneyDtos.PayableExitResp redFlush = cashCheckService.markPayableExit(
                checkId, rowB.getId(), CashCheckItem.EXIT_RED_FLUSH, OP, OPERATOR);
        assertEquals(888L, redFlush.getSourceDocId(), "红冲出口带上对应来源单据");

        cashCheckService.finish(checkId, OP, OPERATOR);
        assertEquals("已完成", cashCheckService.detail(checkId).getCheckStatus(),
                "两出口留痕后钱盘核对归档(P1-7 应付侧闭环)");
    }
}
