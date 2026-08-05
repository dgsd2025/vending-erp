package top.aole.vend.regression;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 穿行场景6:盘亏索赔到账(审计结论:错——索赔应收不在资产公式;赔付收入进不了利润表;
 * P0-6 修复:资产快照 += 索赔应收(claim 申请中);利润表加"其他收入"行;净损耗=损耗−已获赔)。
 *
 * M1 已落地:claim 表 + asset_snapshot.claim_receivable/net_asset + cash_flow.pl_line(其他收入-赔付)
 *   字段全在位;盘亏出库单(净损耗的"损耗"侧数据生产者)过账已通。
 * M3 待接:claim 生命周期(申请中→已到账→写流水)、资产快照公式、净损耗报表。
 */
class Scenario06ClaimTest extends RegressionSupport {

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
    @Disabled("M3-pending:索赔单服务未实现——缺\"申请中→已到账(写 cash_flow 其他收入-赔付+回填 cash_flow_id)→关闭\"生命周期断言;表结构/字段已在本类第一例验过")
    void claimLifecycleToCashFlow() {
        // M3 实装后:盘亏归因吞货 → 索赔单申请中 → 到账 → cash_flow(其他收入-赔付)
    }

    @Test
    @Disabled("M3-pending:资产快照月度生成服务未实现——缺\"净流动资产=库存+待结算+现金+索赔应收−应付\"公式断言(§13.1);claim_receivable 字段已验")
    void assetSnapshotIncludesClaimReceivable() {
        // M3 实装后:申请中索赔 500 → 当月资产快照 claim_receivable=500 且计入 net_asset
    }

    @Test
    @Disabled("M3-pending:损耗报表(净损耗=Σ盘亏−Σclaim已到账)为报表逻辑,依赖索赔到账数据,M3 实装;损耗侧生产者已在本类第二例验过")
    void netShrinkageReport() {
        // M3 实装后:盘亏10.5 已获赔8 → 净损耗2.5
    }
}
