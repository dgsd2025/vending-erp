package top.aole.vend.regression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.bi.dto.BiDtos;
import top.aole.vend.modules.bi.service.BiService;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.pdca.domain.entity.ActionItem;
import top.aole.vend.modules.pdca.dto.PdcaDtos;
import top.aole.vend.modules.pdca.mapper.ActionItemMapper;
import top.aole.vend.modules.pdca.service.ActionItemService;
import top.aole.vend.modules.pdca.service.PdcaMetricService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-7 全链路场景18:BI → PDCA 闭环(vend_test_regression 独立库,每例事务回滚)。
 *
 * 业务主线(经营飞轮的"复盘↺"段):
 *   销售数据 → BI 四象限识别淘汰品(低销量+低毛利率)
 *   → 该品所在的全店动销率偏低 → PDCA 起草一条改进任务(选品环节·动销率指标)
 *   → 任务到期回查:动销率仍未改善(< 目标)→ 未见效升级(ST_ESCALATED)。
 *
 * 跨模块串联真数据源:BiService(M4-1 四象限/矩阵)+ PdcaMetricService(动销率取数)
 * + ActionItemService(登记基线/到期回查三分支),不 mock 任何业务链路。
 */
class Scenario18BiPdcaClosedLoopTest extends RegressionSupport {

    /** 动销率改进目标线(%);当月实际低于它即"未见效"。 */
    private static final BigDecimal SELL_THROUGH_TARGET = new BigDecimal("60");

    @Autowired
    private BiService biService;
    @Autowired
    private PdcaMetricService metricService;
    @Autowired
    private ActionItemService actionItemService;
    @Autowired
    private ActionItemMapper actionItemMapper;

    /** 当月(动销率取数走 YearMonth.now();BI 四象限用同月,两侧口径对齐)。 */
    private final YearMonth ym = YearMonth.now();
    private final String month = ym.format(PERIOD_FMT);

    /** 采购入库:月初 08:00 过账,保证同月销售有加权成本(否则毛利 null 不进象限)。 */
    private void purchase(Long productId, String qty, String price) {
        LocalDateTime t = ym.atDay(1).atTime(8, 0);
        confirmedDoc(DocType.PURCHASE_IN, null, DocService.SOURCE_MANUAL,
                t.toLocalDate(), false, t, new Object[]{productId, qty, price});
    }

    private void sellNormal(Long machineId, Long productId, String qty, String amount, int hour) {
        sale(machineId, productId, qty, amount, "正常", ym.atDay(1).atTime(hour, 0));
    }

    @Test
    @DisplayName("闭环:BI 四象限判淘汰品 → 全店动销率 40%<目标 60% → PDCA 起草选品任务 → 到期回查未见效升级")
    void biQuadrantToPdcaEscalate() {
        Machine m = machine("场景18-复盘机");
        // ---- ① 造销售数据:四象限四类各归其位(阈值=中位数;数字复用 M4-1 已验组合) ----
        Product star = product("场景18-明星东方树叶", null, null);
        Product traffic = product("场景18-引流景田水", null, null);
        Product niche = product("场景18-利基鸭腿", null, null);
        Product dead = product("场景18-淘汰爆珠", null, null);
        purchase(star.getId(), "100", "2");
        purchase(traffic.getId(), "100", "2.7");
        purchase(niche.getId(), "100", "3");
        purchase(dead.getId(), "100", "2.9");
        sellNormal(m.getId(), star.getId(), "30", "150", 12);     // 高销高毛 → 明星
        sellNormal(m.getId(), traffic.getId(), "40", "120", 13);  // 高销低毛 → 引流
        sellNormal(m.getId(), niche.getId(), "5", "50", 14);      // 低销高毛 → 利基
        sellNormal(m.getId(), dead.getId(), "3", "9", 15);        // 低销低毛 → 淘汰

        // 6 个在售但零动销的 SKU,把全店动销率压到 4/10=40%(低于改进目标 60%)
        for (int i = 0; i < 6; i++) {
            product("场景18-滞销陪跑" + i, null, null);
        }

        // ---- ② BI 四象限:淘汰品被正确识别 ----
        BiDtos.QuadrantResp quad = biService.quadrant(month);
        assertEquals(4, quad.getPoints().size(), "四个有成本的 SKU 落象限");
        Map<Long, String> byId = new HashMap<>();
        for (BiDtos.QuadrantPoint p : quad.getPoints()) {
            byId.put(p.getProductId(), p.getQuadrant());
        }
        assertEquals("淘汰", byId.get(dead.getId()), "低销量+低毛利率 → 淘汰象限");
        assertEquals("明星", byId.get(star.getId()));
        assertEquals("引流", byId.get(traffic.getId()));
        assertEquals("利基", byId.get(niche.getId()));

        // 淘汰品在 BI 产品矩阵里销量确实最低(复盘依据可溯源)
        BiDtos.MatrixResp prodMatrix = biService.matrix(month, "product");
        BiDtos.MatrixRow deadRow = prodMatrix.getRows().stream()
                .filter(r -> r.getName().contains("淘汰爆珠")).findFirst().orElse(null);
        assertNotNull(deadRow, "淘汰品应有产品矩阵行");
        assertEquals(0, deadRow.getSalesQty().compareTo(new BigDecimal("3")), "淘汰品销量=3(全场最低)");

        // ---- ③ 全店动销率偏低(PDCA 指标口径取数)----
        PdcaMetricService.Resolved st = metricService.resolve(PdcaMetricService.K_SELL_THROUGH, null);
        assertNotNull(st.value, "动销率应可取数");
        assertEquals(0, st.value.compareTo(new BigDecimal("40.0")),
                "10 在售 SKU 中 4 个动销 → 40%,实际:" + st.value);
        assertTrue(st.value.compareTo(SELL_THROUGH_TARGET) < 0, "动销率低于改进目标,触发起草");

        // ---- ④ PDCA 起草改进任务(选品环节·动销率指标·到期日=今天)----
        PdcaDtos.ItemSaveReq draft = new PdcaDtos.ItemSaveReq();
        draft.setSourceScene(ActionItem.SCENE_SELECT);
        draft.setProblemDesc("BI 四象限识别到淘汰品「场景18-淘汰爆珠」,全店动销率仅 " + st.value + "%");
        draft.setMeasure("下架淘汰品换新品 + 优化货道陈列,提升全店动销率");
        draft.setMetricKey(PdcaMetricService.K_SELL_THROUGH);
        draft.setMetricParam(null);
        draft.setTargetValue(SELL_THROUGH_TARGET);
        draft.setCompareOp(ActionItem.OP_GE);
        draft.setVerifyDate(LocalDate.now());
        Long itemId = actionItemService.create(draft, OP, "场景18测试员");

        ActionItem saved = actionItemMapper.selectById(itemId);
        assertEquals(0, saved.getBaselineValue().compareTo(new BigDecimal("40.0")), "登记即取基线动销率 40%");
        assertEquals(ActionItem.ST_OPEN, saved.getItemStatus(), "起草后进行中");

        // ---- ⑤ 到期回查:动销率仍 40% < 目标 60% → 未见效升级 ----
        PdcaDtos.RecheckResp resp = actionItemService.recheck(itemId, "场景18测试员");
        assertEquals(ActionItem.VR_FAIL, resp.getResult(), "动销率未达标 → 未达");
        assertEquals(ActionItem.ST_ESCALATED, resp.getItemStatus(), "未见效 → 升级");
        assertEquals(0, resp.getCurrentValue().compareTo(new BigDecimal("40.0")), "回查现值=当月动销率");
        assertTrue(resp.getNote().contains("升级"), "回查备注带升级提示");
        assertEquals(ActionItem.ST_ESCALATED, actionItemMapper.selectById(itemId).getItemStatus(),
                "升级态落库");
    }
}
