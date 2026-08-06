package top.aole.vend.regression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.mapper.StocktakeMapper;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景3:月度盘点日(审计结论:断——钱盘发现账户差异后无单据可落地;P1-7 资金调整单修复)。
 *
 * M1 已落地:doc_type=资金调整 十枚举在列,状态机通道全通(草稿→待确认→已确认→已完成),
 *   确认不动库存(postingDeferred,钱账 M3);盘盈/盘亏单通道与过账已通(货盘出口)。
 * M3 待接:资金调整确认 → 生成 cash_flow(pl_line=不入利润表-资金调整);
 *   应付核对不符 → 补录或 settle_bill.direction=红字 两个按钮。
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
    @Disabled("M3-pending:钱账模块未实现——缺\"资金调整单确认→生成 cash_flow(pl_line=不入利润表-资金调整)+账户余额=期初+Σ流水\"的业务断言;doc 通道与 pl_line 字段已在本类前两例验过")
    void cashAdjustGeneratesCashFlow() {
        // M3 实装后:钱盘差异13.2 → 资金调整单确认 → cash_flow 一条,账户余额吻合
    }

    @Test
    @Disabled("M3-pending:应付核对不符的两个出口(补录/生成 settle_bill.direction=红字)依赖结算单服务,M3 实装;direction 字段通道已验")
    void payableMismatchTwoExits() {
        // M3 实装后:应付核对差异 → 补录入库单 或 应付红字冲下一单
    }
}
