package top.aole.vend.regression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.claim.domain.entity.Claim;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.service.ClaimService;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.domain.enums.PlLine;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.mapper.StocktakeItemMapper;
import top.aole.vend.modules.stocktake.mapper.StocktakeMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景6:盘亏索赔到账(审计结论:错——索赔应收不在资产公式;赔付收入进不了利润表;
 * P0-6 修复:资产快照 += 索赔应收(claim 申请中);利润表加"其他收入"行;净损耗=损耗−已获赔)。
 *
 * M1 已落地:claim 表 + asset_snapshot.claim_receivable/net_asset + cash_flow.pl_line(其他收入-赔付)
 *   字段全在位;盘亏出库单(净损耗的"损耗"侧数据生产者)过账已通。
 * M3-4 已接:claim 生命周期(申请中→已到账→写流水+回填 cash_flow_id / 放弃)、
 *   索赔应收查询接口(ClaimService#receivable,资产快照 M3-6 直接调)、净损耗接口。
 */
class Scenario06ClaimTest extends RegressionSupport {

    @Autowired
    private ClaimService claimService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private StocktakeMapper stocktakeMapper;
    @Autowired
    private StocktakeItemMapper stocktakeItemMapper;

    @Test
    @DisplayName("P0-6 公式补漏字段全在位:claim 表(申请中默认/到账三件套)+ 资产快照索赔应收 + 赔付收入行")
    void claimAndAssetFormulaSchemaReady() {
        assertTableExists("yc_vend_claim");
        Map<String, Object> status = assertColumn("yc_vend_claim", "claim_status");
        assertEquals("申请中", String.valueOf(status.get("COLUMN_DEFAULT")),
                "索赔默认申请中(申请中=计入资产快照索赔应收)");
        assertTrue(String.valueOf(status.get("COLUMN_COMMENT")).contains("净损耗"),
                "净损耗=损耗−已获赔 口径写死在字段注释");
        // 到账三件套:实际到账金额/时间/生成的流水
        assertColumn("yc_vend_claim", "received_amount");
        assertColumn("yc_vend_claim", "received_time");
        assertColumn("yc_vend_claim", "cash_flow_id");
        // 来源:盘亏归因(吞货)→ 索赔;盘点明细挂 claim_id
        assertColumn("yc_vend_claim", "source_id");
        assertColumn("yc_vend_stocktake_item", "claim_id");

        // 资产公式修正:净流动资产 = 库存+待结算+现金+索赔应收−应付(§13.1 效力最高)
        Map<String, Object> receivable = assertColumn("yc_vend_asset_snapshot", "claim_receivable");
        assertEquals("NO", String.valueOf(receivable.get("IS_NULLABLE")));
        Map<String, Object> netAsset = assertColumn("yc_vend_asset_snapshot", "net_asset");
        assertTrue(String.valueOf(netAsset.get("COLUMN_COMMENT")).contains("索赔应收"),
                "净资产公式注释含索赔应收项");

        // 赔付收入进利润表:pl_line 枚举含 其他收入-赔付
        Map<String, Object> plLine = assertColumn("yc_vend_cash_flow", "pl_line");
        assertTrue(String.valueOf(plLine.get("COLUMN_COMMENT")).contains("其他收入-赔付"));
    }

    @Test
    @DisplayName("净损耗的\"损耗\"侧数据生产者已通:盘亏出库单过账,仓库账/流水金额正确")
    void lossOutDocProducesShrinkageData() {
        Product p = product("被吞的红牛", null, null);
        stockWarehouse(p.getId(), "10"); // 10 × 3.5
        confirmedDoc(DocType.LOSS_OUT, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{p.getId(), "3", "3.5"});
        assertEquals(0, stockService.getWarehouseStock(p.getId()).compareTo(new BigDecimal("7")),
                "盘亏−3:损耗报表的分子有数据生产者");
    }

    @Test
    @DisplayName("索赔生命周期(M3-4 实装):盘亏归因吞货 → 申请中(回填 claim_id)→ 到账 → cash_flow(其他收入-赔付)+ 回填 cash_flow_id")
    void claimLifecycleToCashFlow() {
        // 盘亏归因吞货:直造已完成盘点 + 吞货差异行(−3 × 3.5 = −10.5)
        Stocktake st = new Stocktake();
        st.setStNo("PD-S06-" + SEQ.incrementAndGet());
        st.setScopeType(Stocktake.SCOPE_WAREHOUSE);
        st.setSnapshotTime(LocalDateTime.now());
        st.setStStatus(Stocktake.ST_DONE);
        stocktakeMapper.insert(st);
        StocktakeItem item = new StocktakeItem();
        item.setStocktakeId(st.getId());
        item.setProductId(901L);
        item.setBookQty(new BigDecimal("10"));
        item.setActualQty(new BigDecimal("7"));
        item.setDiffQty(new BigDecimal("-3"));
        item.setDiffAmount(new BigDecimal("-10.50"));
        item.setDiffReason(StocktakeItem.REASON_SWALLOW);
        item.setOfflineExempt(false);
        stocktakeItemMapper.insert(item);

        // 发起申请(对象厂家,金额=盘亏成本额)→ 申请中
        ClaimDtos.CreateReq create = new ClaimDtos.CreateReq();
        create.setClaimTarget(Claim.TARGET_FACTORY);
        create.setAmount(new BigDecimal("10.50"));
        create.setSourceId(st.getId());
        create.setStocktakeItemIds(Arrays.asList(item.getId()));
        Long claimId = claimService.create(create, 9L, OPERATOR);
        assertEquals(Claim.STATUS_PENDING, claimService.detail(claimId).getClaimStatus());
        assertEquals(claimId, stocktakeItemMapper.selectById(item.getId()).getClaimId(),
                "吞货可索赔 → 盘点明细回填 claim_id");

        // 赔付凭证必传 → 到账登记
        MoneyDtos.AccountCreateReq accReq = new MoneyDtos.AccountCreateReq();
        accReq.setAccountName("老板微信-S06-" + SEQ.incrementAndGet());
        accReq.setAccountType("微信");
        Long accountId = accountService.create(accReq, 9L, OPERATOR);
        attachmentService.upload(ClaimService.ATTACH_REF_TYPE, claimId, "赔付凭证",
                "厂家赔付.png", "png".getBytes(StandardCharsets.UTF_8), 9L, OPERATOR);
        ClaimDtos.ReceiveReq receive = new ClaimDtos.ReceiveReq();
        receive.setAccountId(accountId);
        ClaimDtos.ClaimRow row = claimService.receive(claimId, receive, 9L, OPERATOR);

        assertEquals(Claim.STATUS_RECEIVED, row.getClaimStatus());
        assertNotNull(row.getCashFlowId());
        CashFlow flow = cashFlowMapper.selectById(row.getCashFlowId());
        assertEquals("索赔到账", flow.getCategory());
        assertEquals(PlLine.OTHER_INCOME_CLAIM.getLabel(), flow.getPlLine(),
                "赔付收入进利润表 其他收入-赔付 行(P0-6 修复点)");
        assertEquals(0, flow.getAmount().compareTo(new BigDecimal("10.50")));
        assertEquals(0, accountService.balanceOf(accountId).compareTo(new BigDecimal("10.50")));
    }

    @Test
    @DisplayName("索赔应收接口(M3-4 实装,资产快照 M3-6 取数口):申请中 500 → receivable=500 且到账/放弃退出")
    void assetSnapshotIncludesClaimReceivable() {
        BigDecimal base = claimService.receivable();
        ClaimDtos.CreateReq create = new ClaimDtos.CreateReq();
        create.setClaimTarget(Claim.TARGET_FACTORY);
        create.setAmount(new BigDecimal("500"));
        Long claimId = claimService.create(create, 9L, OPERATOR);
        // 申请中索赔 500 → 「索赔应收」+500(M3-6 资产快照 claim_receivable/net_asset 直接调本接口)
        assertEquals(0, claimService.receivable().subtract(base).compareTo(new BigDecimal("500")),
                "净流动资产 = 库存+待结算+现金+索赔应收−应付:索赔应收项取数口就绪");

        ClaimDtos.AbandonReq abandon = new ClaimDtos.AbandonReq();
        abandon.setRemark("穿行验证:放弃退出应收");
        claimService.abandon(claimId, abandon, 9L, OPERATOR);
        assertEquals(0, claimService.receivable().subtract(base).compareTo(BigDecimal.ZERO),
                "放弃后退出索赔应收");
    }

    @Test
    @DisplayName("净损耗报表口(M3-4 实装):盘亏 10.5 已获赔 8 → 净损耗 2.5")
    void netShrinkageReport() {
        ClaimDtos.NetShrinkageResp before = claimService.netShrinkage(null, null);

        Product p = product("被吞的脉动", null, null);
        stockWarehouse(p.getId(), "10");
        confirmedDoc(DocType.LOSS_OUT, null, DocService.SOURCE_MANUAL,
                LocalDate.now(), false, null, new Object[]{p.getId(), "3", "3.5"}); // 损耗 10.5

        ClaimDtos.CreateReq create = new ClaimDtos.CreateReq();
        create.setClaimTarget(Claim.TARGET_FACTORY);
        create.setAmount(new BigDecimal("10.50"));
        Long claimId = claimService.create(create, 9L, OPERATOR);
        MoneyDtos.AccountCreateReq accReq = new MoneyDtos.AccountCreateReq();
        accReq.setAccountName("老板微信-S06N-" + SEQ.incrementAndGet());
        accReq.setAccountType("微信");
        Long accountId = accountService.create(accReq, 9L, OPERATOR);
        attachmentService.upload(ClaimService.ATTACH_REF_TYPE, claimId, "赔付凭证",
                "赔付.png", "png".getBytes(StandardCharsets.UTF_8), 9L, OPERATOR);
        ClaimDtos.ReceiveReq receive = new ClaimDtos.ReceiveReq();
        receive.setAccountId(accountId);
        receive.setReceivedAmount(new BigDecimal("8.00"));
        claimService.receive(claimId, receive, 9L, OPERATOR);

        ClaimDtos.NetShrinkageResp after = claimService.netShrinkage(null, null);
        assertEquals(0, after.getLossAmount().subtract(before.getLossAmount())
                .compareTo(new BigDecimal("10.50")), "损耗 +10.5");
        assertEquals(0, after.getCompensatedAmount().subtract(before.getCompensatedAmount())
                .compareTo(new BigDecimal("8.00")), "已获赔 +8");
        assertEquals(0, after.getNetAmount().subtract(before.getNetAmount())
                .compareTo(new BigDecimal("2.50")), "净损耗 = 损耗 − 已获赔 = 2.5");
    }
}
