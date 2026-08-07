package top.aole.vend.modules.bi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.bi.dto.BiDtos;
import top.aole.vend.modules.bi.service.BiService;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.regression.RegressionSupport;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-1 BI 集成测试(vend_test_bi 独立库,每例事务回滚):
 * 四象限分类正确性 / 维度聚合 vs SQL 直查一致 / §13 口径(兑换/测试/线下/退款)/
 * 缺货损失公式 / 无数据格 null 不造假 / 品类动销率+损耗 / 时段星期 / 连带支付 / 调价对比 / 供应商归属。
 */
@ActiveProfiles("test-bi")
class BiServiceTest extends RegressionSupport {

    private static final String M = "2026-05";
    private static final LocalDateTime PURCHASE_T = LocalDateTime.parse("2026-04-01T09:00:00");

    @Autowired
    private BiService biService;

    // ============================== 造数工具 ==============================

    /** 采购入库(自定价,4 月过账 → 5 月销售有成本) */
    private void purchase(Long productId, String qty, String price) {
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                PURCHASE_T.toLocalDate(), false, PURCHASE_T, new Object[]{productId, qty, price});
    }

    /** 带供应商的采购入库 */
    private void purchaseFrom(Long supplierId, Long productId, String qty, String price,
                              LocalDateTime bizTime) {
        DocCreateReq r = req(DocType.PURCHASE_IN, null, null, bizTime.toLocalDate(),
                new Object[]{productId, qty, price});
        r.setSupplierId(supplierId);
        Long id = docService.createDocWithSource(r, OP, DocService.SOURCE_MANUAL);
        docService.submit(id, OP);
        docService.confirm(id, OP, false, bizTime);
    }

    /** 销售(可带货道/支付方式) */
    private SaleRecord saleAt(Long machineId, Long productId, String qty, String amount,
                              String type, String time, String slotNo, String payMethod) {
        SaleRecord s = sale(machineId, productId, qty, amount, type, LocalDateTime.parse(time));
        if (slotNo != null || payMethod != null) {
            s.setSlotNo(slotNo);
            s.setPayMethod(payMethod);
            saleRecordMapper.updateById(s);
        }
        return s;
    }

    private Long supplier(String name) {
        jdbc.update("INSERT INTO yc_vend_supplier (supplier_code, supplier_name) VALUES (?,?)",
                "BIS" + SEQ.incrementAndGet(), name);
        return jdbc.queryForObject("SELECT MAX(id) FROM yc_vend_supplier", Long.class);
    }

    private void slot(Long machineId, String slotNo, Long productId, int capacity) {
        jdbc.update("INSERT INTO yc_vend_slot (machine_id, slot_no, product_id, capacity) VALUES (?,?,?,?)",
                machineId, slotNo, productId, capacity);
    }

    /** 已完成盘点 + 盘亏行(损耗数据源) */
    private void loss(Long machineId, Long productId, String slotNo, String reason,
                      String diffQty, String diffAmount, String snapshotTime, boolean exempt) {
        jdbc.update("INSERT INTO yc_vend_stocktake (st_no, scope_type, machine_id, snapshot_time, st_status) "
                        + "VALUES (?,?,?,?,'已完成')",
                "BIST" + SEQ.incrementAndGet(), machineId == null ? "仓库" : "机器", machineId, snapshotTime);
        Long stId = jdbc.queryForObject("SELECT MAX(id) FROM yc_vend_stocktake", Long.class);
        jdbc.update("INSERT INTO yc_vend_stocktake_item (stocktake_id, product_id, slot_no, book_qty, "
                        + "actual_qty, diff_qty, diff_amount, diff_reason, offline_exempt) "
                        + "VALUES (?,?,?,0,0,?,?,?,?)",
                stId, productId, slotNo, diffQty, diffAmount, reason, exempt ? 1 : 0);
    }

    private BiDtos.MatrixRow row(BiDtos.MatrixResp resp, String name) {
        return resp.getRows().stream().filter(r -> r.getName().contains(name)).findFirst().orElse(null);
    }

    // ============================== 1. 四象限 ==============================

    @Test
    @DisplayName("四象限:四类造数各归其位(阈值=中位数),明星/引流/利基/淘汰分类正确")
    void quadrantFourClasses() {
        Machine m = machine("四象限机");
        Product star = product("四象限-明星东方树叶", null, null);
        Product traffic = product("四象限-引流景田水", null, null);
        Product niche = product("四象限-利基鸭腿", null, null);
        Product dead = product("四象限-淘汰爆珠", null, null);
        purchase(star.getId(), "100", "2");
        purchase(traffic.getId(), "100", "2.7");
        purchase(niche.getId(), "100", "3");
        purchase(dead.getId(), "100", "2.9");
        sale(m.getId(), star.getId(), "30", "150", "正常", LocalDateTime.parse("2026-05-10T12:00:00"));
        sale(m.getId(), traffic.getId(), "40", "120", "正常", LocalDateTime.parse("2026-05-10T13:00:00"));
        sale(m.getId(), niche.getId(), "5", "50", "正常", LocalDateTime.parse("2026-05-10T14:00:00"));
        sale(m.getId(), dead.getId(), "3", "9", "正常", LocalDateTime.parse("2026-05-10T15:00:00"));

        BiDtos.QuadrantResp resp = biService.quadrant(M);
        assertEquals(4, resp.getPoints().size());
        // 阈值 = 中位数:销量 [3,5,30,40] → 17.5;毛利率 [3.33,10,60,70] → 35
        assertEquals(0, resp.getQtyMedian().compareTo(new BigDecimal("17.5")));
        assertEquals(0, resp.getMarginMedian().compareTo(new BigDecimal("35")));
        Map<String, String> byName = new java.util.HashMap<>();
        for (BiDtos.QuadrantPoint p : resp.getPoints()) {
            byName.put(p.getName(), p.getQuadrant());
        }
        assertEquals("明星", byName.get("四象限-明星东方树叶"));
        assertEquals("引流", byName.get("四象限-引流景田水"));
        assertEquals("利基", byName.get("四象限-利基鸭腿"));
        assertEquals("淘汰", byName.get("四象限-淘汰爆珠"));
    }

    // ============================== 2. 维度聚合 vs SQL 直查 ==============================

    @Test
    @DisplayName("机器维聚合 = SQL 直查(销售额口径:测试不计/兑换线下收入0/退款负)")
    void machineDimMatchesSql() {
        Machine m1 = machine("直查1号机");
        Machine m2 = machine("直查2号机");
        Product p = product("直查东鹏", null, null);
        purchase(p.getId(), "100", "2");
        sale(m1.getId(), p.getId(), "3", "15", "正常", LocalDateTime.parse("2026-05-05T10:00:00"));
        sale(m1.getId(), p.getId(), "1", "-5", "退款", LocalDateTime.parse("2026-05-05T11:00:00"));
        sale(m1.getId(), p.getId(), "2", "10", "兑换", LocalDateTime.parse("2026-05-05T12:00:00"));
        sale(m2.getId(), p.getId(), "4", "20", "正常", LocalDateTime.parse("2026-05-06T10:00:00"));
        sale(m2.getId(), p.getId(), "9", "45", "测试", LocalDateTime.parse("2026-05-06T11:00:00"));

        BiDtos.MatrixResp resp = biService.matrix(M, "machine");
        for (Machine m : Arrays.asList(m1, m2)) {
            BigDecimal sqlAmt = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(CASE WHEN order_type IN ('兑换','线下补录') THEN 0 "
                            + "ELSE amount_received END),0) FROM yc_vend_sale_record "
                            + "WHERE machine_id=? AND biz_period=? AND order_type<>'测试' AND is_deleted=0",
                    BigDecimal.class, m.getId(), M);
            BiDtos.MatrixRow r = row(resp, m.getMachineName());
            assertNotNull(r, m.getMachineName() + " 应有行");
            assertEquals(0, r.getSalesAmt().compareTo(sqlAmt),
                    m.getMachineName() + " 聚合应与 SQL 直查一致:" + r.getSalesAmt() + " vs " + sqlAmt);
        }
        // m1: 15-5=10;毛利 = 10 − (3-1+2)×2 = 2(兑换收入0但计成本)
        BiDtos.MatrixRow r1 = row(resp, "直查1号机");
        assertEquals(0, r1.getSalesAmt().compareTo(new BigDecimal("10")));
        assertEquals(0, r1.getGrossProfit().compareTo(new BigDecimal("2")));
    }

    // ============================== 3. §13 三口径 ==============================

    @Test
    @DisplayName("口径:兑换/线下补录收入 0(销量照计),测试完全不入,退款销量与金额均为负")
    void orderTypeCaliber() {
        Machine m = machine("口径机");
        Product p = product("口径可乐", null, null);
        purchase(p.getId(), "100", "2");
        sale(m.getId(), p.getId(), "1", "10", "正常", LocalDateTime.parse("2026-05-08T10:00:00"));
        sale(m.getId(), p.getId(), "1", "5", "兑换", LocalDateTime.parse("2026-05-08T11:00:00"));
        sale(m.getId(), p.getId(), "1", "7", "线下补录", LocalDateTime.parse("2026-05-08T12:00:00"));
        sale(m.getId(), p.getId(), "1", "99", "测试", LocalDateTime.parse("2026-05-08T13:00:00"));
        sale(m.getId(), p.getId(), "1", "-3", "退款", LocalDateTime.parse("2026-05-08T14:00:00"));

        BiDtos.MatrixResp resp = biService.matrix(M, "product");
        BiDtos.MatrixRow r = row(resp, "口径可乐");
        assertNotNull(r);
        // 销售额 = 10 + 0 + 0 − 3 = 7(测试的 99 不入)
        assertEquals(0, r.getSalesAmt().compareTo(new BigDecimal("7")));
        // 销量 = 1+1+1−1 = 2(测试不计)
        assertEquals(0, r.getSalesQty().compareTo(new BigDecimal("2")));
    }

    // ============================== 4. 缺货损失公式 ==============================

    @Test
    @DisplayName("缺货损失 = 缺货天数 × 有货日日均毛利(锚点+增量逐日回放);无锚点组合不出行(不造假)")
    void stockoutLossFormula() {
        Machine m = machine("缺货机");
        Product p = product("缺货东鹏", null, null);
        slot(m.getId(), "1", p.getId(), 20);
        purchase(p.getId(), "100", "2");
        // 锚点:05-01 08:00 机内 10 件
        stockService.recordMachineSnapshot(m.getId(), p.getId(), "1", new BigDecimal("10"),
                "盘点", LocalDateTime.parse("2026-05-01T08:00:00"), OP);
        // 05-02 卖 5(¥25)、05-03 卖 5(¥25)→ 03 日末 0;之后一直空
        sale(m.getId(), p.getId(), "5", "25", "正常", LocalDateTime.parse("2026-05-02T12:00:00"));
        sale(m.getId(), p.getId(), "5", "25", "正常", LocalDateTime.parse("2026-05-03T12:00:00"));
        // 05-06 23:00 再落一个 0 快照(推进 dataAsOf → 覆盖 6 天)
        stockService.recordMachineSnapshot(m.getId(), p.getId(), "1", BigDecimal.ZERO,
                "后台缺货页", LocalDateTime.parse("2026-05-06T23:00:00"), OP);
        // 无锚点对照组:绑了货道、有销量,但没有任何快照 → 不得出行
        Machine m2 = machine("无锚机");
        Product p2 = product("无锚水", null, null);
        slot(m2.getId(), "1", p2.getId(), 20);
        purchase(p2.getId(), "100", "1");
        sale(m2.getId(), p2.getId(), "2", "6", "正常", LocalDateTime.parse("2026-05-04T10:00:00"));

        BiDtos.StockoutLossResp resp = biService.stockoutLoss(M);
        assertEquals(1, resp.getRows().size(), "只有有锚点的组合出行");
        BiDtos.StockoutLossRow r = resp.getRows().get(0);
        assertEquals(m.getId(), r.getMachineId());
        // 覆盖 6 天:1-2 有货、3-6 缺货(日末≤0)
        assertEquals(6, r.getCoverageDays());
        assertEquals(4, r.getStockoutDays());
        // 月毛利 = (25−10)+(25−10)=30;有货日 2 天 → 日均 15;损失 = 15×4 = 60
        assertEquals(0, r.getDailyGross().compareTo(new BigDecimal("15")));
        assertEquals(0, r.getEstLoss().compareTo(new BigDecimal("60")));
        assertEquals(0, resp.getTotalEstLoss().compareTo(new BigDecimal("60")));
        // 机器维矩阵的缺货损失格 = 同一数
        BiDtos.MatrixRow mr = row(biService.matrix(M, "machine"), "缺货机");
        assertEquals(0, mr.getStockoutLossAmt().compareTo(new BigDecimal("60")));
    }

    // ============================== 5. 无数据 = null 不造假 ==============================

    @Test
    @DisplayName("无数据格 null:无采购史毛利显 null 且四象限单列「成本待补」;无完成订货单实收差异率 null")
    void nullNotFake() {
        Machine m = machine("无档机");
        Product p = product("无成本新品", null, null);
        // 不采购直接卖(无采购史 → 成本 NULL,§13.2-6)
        sale(m.getId(), p.getId(), "3", "15", "正常", LocalDateTime.parse("2026-05-09T10:00:00"));

        BiDtos.MatrixResp prodResp = biService.matrix(M, "product");
        BiDtos.MatrixRow r = row(prodResp, "无成本新品");
        assertNotNull(r);
        assertEquals(Boolean.FALSE, r.getHasCost());
        assertNull(r.getGrossProfit(), "无成本毛利必须 null,禁 0 充数");
        assertNull(r.getMarginPct());
        assertNull(r.getLossAmt(), "没盘过点损耗格 null");
        assertNull(r.getStockoutLossAmt());

        BiDtos.QuadrantResp q = biService.quadrant(M);
        assertTrue(q.getPoints().stream().noneMatch(pt -> pt.getProductId().equals(p.getId())),
                "无成本单品不得硬塞象限");
        assertTrue(q.getNoCostPoints().stream().anyMatch(pt -> pt.getProductId().equals(p.getId())),
                "无成本单品单列「成本待补」");

        // 供应商维:有采购但订货单没完成 → 实收差异率 null
        Long sup = supplier("差异未知供应商");
        purchaseFrom(sup, p.getId(), "10", "2", LocalDateTime.parse("2026-05-01T09:00:00"));
        BiDtos.MatrixRow sr = row(biService.matrix(M, "supplier"), "差异未知供应商");
        assertNotNull(sr);
        assertNull(sr.getReceiveDiffRate(), "无已完成订货单 → 实收差异率 null");
    }

    // ============================== 6. 品类维:动销率 + 损耗 ==============================

    @Test
    @DisplayName("品类维:动销率=有销量SKU÷在售SKU;损耗按品类汇总(线下豁免行不算)")
    void categoryDim() {
        Machine m = machine("品类机");
        Product drinkSold = product("品类-卖动的茶", null, null);
        Product drinkDead = product("品类-卖不动的汽水", null, null);
        Product noodle = product("品类-泡面王", null, null);
        jdbc.update("UPDATE yc_vend_product SET category='测试饮料' WHERE id IN (?,?)",
                drinkSold.getId(), drinkDead.getId());
        jdbc.update("UPDATE yc_vend_product SET category='测试泡面' WHERE id=?", noodle.getId());
        purchase(drinkSold.getId(), "100", "2");
        purchase(noodle.getId(), "100", "3");
        sale(m.getId(), drinkSold.getId(), "10", "50", "正常", LocalDateTime.parse("2026-05-11T10:00:00"));
        sale(m.getId(), noodle.getId(), "4", "20", "正常", LocalDateTime.parse("2026-05-11T11:00:00"));
        // 损耗:饮料 2 件 ¥4;另一行线下豁免不算
        loss(m.getId(), drinkSold.getId(), "1", "吞货掉货", "-2", "-4", "2026-05-15 10:00:00", false);
        loss(m.getId(), drinkSold.getId(), "1", "吞货掉货", "-9", "-18", "2026-05-16 10:00:00", true);

        BiDtos.MatrixResp resp = biService.matrix(M, "category");
        BiDtos.MatrixRow drink = row(resp, "测试饮料");
        assertNotNull(drink);
        assertEquals(1, drink.getSoldSkuCount());
        assertEquals(2, drink.getOnSaleSkuCount());
        assertEquals(0, drink.getActiveRate().compareTo(new BigDecimal("50")), "动销率 1/2=50%");
        assertEquals(0, drink.getLossQty().compareTo(new BigDecimal("2")), "豁免行不算损耗");
        assertEquals(0, drink.getLossAmt().compareTo(new BigDecimal("4")));
        BiDtos.MatrixRow nd = row(resp, "测试泡面");
        assertEquals(0, nd.getActiveRate().compareTo(new BigDecimal("100")));
        assertNull(nd.getLossQty(), "没损耗的品类格 null");
    }

    // ============================== 7. 时段/星期 ==============================

    @Test
    @DisplayName("时段维:2 小时桶与星期行落位正确;毛利格=null(§10.1 该维不看毛利)")
    void timeslotDim() {
        Machine m = machine("时段机");
        Product p = product("时段咖啡", null, null);
        purchase(p.getId(), "100", "2");
        // 2026-05-04 是周一
        sale(m.getId(), p.getId(), "2", "10", "正常", LocalDateTime.parse("2026-05-04T09:30:00"));
        sale(m.getId(), p.getId(), "1", "5", "正常", LocalDateTime.parse("2026-05-04T19:00:00"));

        BiDtos.MatrixResp resp = biService.matrix(M, "timeslot");
        assertEquals(12, resp.getRows().size());
        BiDtos.MatrixRow b8 = resp.getRows().stream()
                .filter(r -> "8-10 点".equals(r.getName())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, b8.getSalesAmt().compareTo(new BigDecimal("10")));
        assertEquals(0, b8.getSalesQty().compareTo(new BigDecimal("2")));
        BiDtos.MatrixRow b18 = resp.getRows().stream()
                .filter(r -> "18-20 点".equals(r.getName())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, b18.getSalesAmt().compareTo(new BigDecimal("5")));
        assertNull(b8.getGrossProfit(), "时段维毛利格必须 null(§10.1)");
        assertNotNull(resp.getWeekdayRows());
        BiDtos.MatrixRow mon = resp.getWeekdayRows().stream()
                .filter(r -> "周一".equals(r.getName())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, mon.getSalesAmt().compareTo(new BigDecimal("15")));
        BiDtos.MatrixRow sun = resp.getWeekdayRows().stream()
                .filter(r -> "周日".equals(r.getName())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, sun.getSalesAmt().compareTo(BigDecimal.ZERO));
    }

    // ============================== 8. 客单价/连带/支付 ==============================

    @Test
    @DisplayName("连带:同订单多件出 TOP 组合;客单价=正常订单均值;支付方式净额占比")
    void basket() {
        Machine m = machine("连带机");
        Product a = product("连带-泡面", null, null);
        Product b = product("连带-烤肠", null, null);
        purchase(a.getId(), "100", "2");
        purchase(b.getId(), "100", "1");
        // 订单 O1:泡面+烤肠 同机同秒(会话键=machine+biz_time,微信 3+4);O2:泡面(支付宝 5);退款(微信 −3)
        saleAt(m.getId(), a.getId(), "1", "3", "正常", "2026-05-12T12:00:00", "1", "微信");
        saleAt(m.getId(), b.getId(), "1", "4", "正常", "2026-05-12T12:00:00", "2", "微信");
        saleAt(m.getId(), a.getId(), "1", "5", "正常", "2026-05-13T12:00:00", "1", "支付宝");
        saleAt(m.getId(), a.getId(), "1", "-3", "退款", "2026-05-14T12:00:00", "1", "微信");

        BiDtos.BasketResp resp = biService.basket(M);
        assertEquals(2, resp.getOrderCount());
        assertEquals(0, resp.getAvgOrderValue().compareTo(new BigDecimal("6")), "(7+5)/2=6");
        assertEquals(1, resp.getMultiItemOrderCount());
        assertEquals(0, resp.getMultiItemSharePct().compareTo(new BigDecimal("50")));
        assertEquals(1, resp.getCombos().size());
        BiDtos.ComboRow combo = resp.getCombos().get(0);
        assertTrue((combo.getProductA() + combo.getProductB()).contains("泡面"));
        assertTrue((combo.getProductA() + combo.getProductB()).contains("烤肠"));
        assertEquals(1, combo.getTimes());
        // 支付方式:微信 3+4−3=4,支付宝 5,合计 9
        BiDtos.PayMethodRow wx = resp.getPayMethods().stream()
                .filter(r -> "微信".equals(r.getPayMethod())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, wx.getAmt().compareTo(new BigDecimal("4")));
        BiDtos.PayMethodRow ali = resp.getPayMethods().stream()
                .filter(r -> "支付宝".equals(r.getPayMethod())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, ali.getAmt().compareTo(new BigDecimal("5")));
        assertEquals(0, ali.getSharePct().compareTo(new BigDecimal("55.6")), "5/9=55.6%");
    }

    // ============================== 9. 调价前后 14 天对比 ==============================

    @Test
    @DisplayName("调价对比:前后窗日均销量正确;数据不满 14 天标 partial 按实际天数算")
    void priceCompare() {
        Machine m = machine("调价机");
        Product p = product("调价乌龙茶", null, null);
        purchase(p.getId(), "100", "2");
        // 前窗:05-01 ~ 05-05 每天 1 件 ¥3
        for (int d = 1; d <= 5; d++) {
            sale(m.getId(), p.getId(), "1", "3",
                    "正常", LocalDateTime.parse(String.format("2026-05-%02dT10:00:00", d)));
        }
        // 05-06 调价 3 → 4
        jdbc.update("INSERT INTO yc_vend_price_log (product_id, old_price, new_price, change_source, effect_date) "
                + "VALUES (?,?,?,?,?)", p.getId(), "3", "4", "导入侦测", "2026-05-06");
        // 后窗:05-06 ~ 05-08 每天 1 件 ¥4(dataAsOf=05-08 → 后窗只有 3 天,partial)
        for (int d = 6; d <= 8; d++) {
            sale(m.getId(), p.getId(), "1", "4",
                    "正常", LocalDateTime.parse(String.format("2026-05-%02dT10:00:00", d)));
        }

        BiDtos.PriceCompareResp resp = biService.priceCompare();
        BiDtos.PriceCompareRow r = resp.getRows().stream()
                .filter(x -> x.getProductId().equals(p.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("2026-05-06", r.getEffectDate());
        assertEquals(0, r.getOldPrice().compareTo(new BigDecimal("3")));
        assertEquals(0, r.getNewPrice().compareTo(new BigDecimal("4")));
        assertEquals(Boolean.TRUE, r.getPartial());
        assertEquals(3, r.getAfterDays());
        // 前窗 5 件/14 天 = 0.36;后窗 3 件/3 天 = 1.00
        assertEquals(0, r.getBeforeAvgQty().compareTo(new BigDecimal("0.36")));
        assertEquals(0, r.getAfterAvgQty().compareTo(new BigDecimal("1.00")));
        assertNotNull(r.getQtyChangePct());
        assertTrue(r.getQtyChangePct().signum() > 0, "涨价后样例日均升 → 变化率为正");
    }

    // ============================== 10. 供应商维:归属 + 实收差异率 ==============================

    @Test
    @DisplayName("供应商维:采购额按月聚合;毛利按主供归属;已完成订货单实收差异率=1−实收/订购")
    void supplierDim() {
        Machine m = machine("供应商机");
        Product p = product("供应商-矿泉水", null, null);
        Long s1 = supplier("陈老板BI测试");
        purchaseFrom(s1, p.getId(), "100", "2", LocalDateTime.parse("2026-05-01T09:00:00"));
        sale(m.getId(), p.getId(), "10", "50", "正常", LocalDateTime.parse("2026-05-10T10:00:00"));
        // 已完成订货单:订 100 收 95 → 差异率 5%
        jdbc.update("INSERT INTO yc_vend_purchase_order (po_no, supplier_id, po_status) VALUES (?,?,'已完成')",
                "BIPO" + SEQ.incrementAndGet(), s1);
        Long poId = jdbc.queryForObject("SELECT MAX(id) FROM yc_vend_purchase_order", Long.class);
        jdbc.update("INSERT INTO yc_vend_purchase_order_item (po_id, product_id, qty_ordered, qty_received) "
                + "VALUES (?,?,100,95)", poId, p.getId());

        BiDtos.MatrixResp resp = biService.matrix(M, "supplier");
        BiDtos.MatrixRow r = row(resp, "陈老板BI测试");
        assertNotNull(r);
        assertEquals(0, r.getPurchaseAmt().compareTo(new BigDecimal("200")), "5 月采购 100×2");
        // 毛利贡献 = 50 − 10×2 = 30(该 SKU 主供=陈老板)
        assertEquals(0, r.getMarginContribution().compareTo(new BigDecimal("30")));
        assertEquals(1, r.getAttributedSkuCount());
        assertEquals(0, r.getReceiveDiffRate().compareTo(new BigDecimal("5")), "(100−95)/100=5%");
    }

    // ============================== 11. 货道维 ==============================

    @Test
    @DisplayName("货道维:出货量按货道聚合、货道日均毛利;单 SKU 单货道给现量,多货道标拆不开")
    void slotDim() {
        Machine m = machine("货道机");
        Product single = product("货道-单道品", null, null);
        Product multi = product("货道-双道品", null, null);
        slot(m.getId(), "11", single.getId(), 20);
        slot(m.getId(), "12", multi.getId(), 20);
        slot(m.getId(), "13", multi.getId(), 20);
        purchase(single.getId(), "100", "2");
        purchase(multi.getId(), "100", "2");
        saleAt(m.getId(), single.getId(), "4", "20", "正常", "2026-05-20T10:00:00", "11", "微信");
        saleAt(m.getId(), multi.getId(), "2", "10", "正常", "2026-05-20T11:00:00", "12", "微信");

        BiDtos.MatrixResp resp = biService.matrix(M, "slot");
        BiDtos.MatrixRow r11 = row(resp, "11 道");
        assertNotNull(r11);
        assertEquals(0, r11.getSalesQty().compareTo(new BigDecimal("4")));
        assertEquals(0, r11.getSalesAmt().compareTo(new BigDecimal("20")));
        // 毛利 = 20 − 4×2 = 12;日均毛利 = 12 ÷ 本月已过天数
        assertEquals(0, r11.getGrossProfit().compareTo(new BigDecimal("12")));
        assertNotNull(r11.getDailyAvgAmt());
        assertEquals(Boolean.FALSE, r11.getEstShared(), "单 SKU 单货道 → 可给现量");
        assertNotNull(r11.getEstQty());
        BiDtos.MatrixRow r12 = row(resp, "12 道");
        assertEquals(Boolean.TRUE, r12.getEstShared(), "同 SKU 绑两道 → 拆不开,标 shared");
        assertNull(r12.getEstQty());
        // 没销量的 13 道也出行(planogram 骨架)
        assertNotNull(row(resp, "13 道"));
    }
}
