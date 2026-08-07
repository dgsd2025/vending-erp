package top.aole.vend.modules.pdca.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.pdca.domain.entity.ActionItem;
import top.aole.vend.modules.pdca.dto.PdcaDtos;
import top.aole.vend.modules.pdca.mapper.ActionItemMapper;
import top.aole.vend.modules.pdca.mapper.PdcaQueryMapper;
import top.aole.vend.modules.report.service.MoneyLightsService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 六环节 PDCA 看板(§8.3 总表的 C 列点亮):每环节本期 C 指标红绿灯。
 * 口径全部走 PdcaMetricService 注册表(与到期回查同一把尺),阈值在此集中定义;
 * 数据不足亮灰灯(gray)而不是绿灯——没数据 ≠ 健康(铁律:缺失=null,不硬编码默认好)。
 *
 * BI 票(M4-1)并行在做多维指标,动销率等此处先用 sale_record 简版,接口位已留:
 * 换源只动 PdcaMetricService 对应 resolver,看板/回查代码不动。
 */
@Service
@RequiredArgsConstructor
public class PdcaBoardService {

    // ---------- 阈值(mockup p11 目标口径) ----------
    /** 补货:售罄货道占比 红/黄线(目标 <5%) */
    public static final BigDecimal STOCKOUT_RED = new BigDecimal("5");
    public static final BigDecimal STOCKOUT_AMBER = new BigDecimal("2");
    /** 补货:带回率 红/黄线(目标 <10%) */
    public static final BigDecimal TAKEBACK_RED = new BigDecimal("10");
    public static final BigDecimal TAKEBACK_AMBER = new BigDecimal("5");
    /** 采购:进价环比涨幅红线(与录单比价 20% 黄灯同尺) */
    public static final BigDecimal PRICE_RISE_RED = new BigDecimal("20");
    /** 选品:动销率 红/黄线(目标 ≥75%) */
    public static final BigDecimal SELL_THROUGH_RED = new BigDecimal("50");
    public static final BigDecimal SELL_THROUGH_AMBER = new BigDecimal("75");
    /** 盘点:当月损耗额 红/黄线(目标 <¥30/月;>¥50 对齐盘点老板确认线) */
    public static final BigDecimal LOSS_RED = new BigDecimal("50");
    public static final BigDecimal LOSS_AMBER = new BigDecimal("30");
    /** 盘点:账实符合率黄线(目标 ≥98%) */
    public static final BigDecimal MATCH_AMBER = new BigDecimal("98");

    private final PdcaMetricService metricService;
    private final PdcaQueryMapper queryMapper;
    private final ActionItemMapper itemMapper;
    private final MoneyLightsService moneyLightsService;

    public PdcaDtos.BoardResp board() {
        PdcaDtos.BoardResp resp = new PdcaDtos.BoardResp();
        String period = metricService.currentPeriod();
        resp.setPeriod(period);

        // ① 补货:售罄货道占比 + 当月平均带回率
        PdcaDtos.BoardScene replenish = scene(ActionItem.SCENE_REPLENISH, "🤖", "售罄货道 <5% · 带回率 <10%");
        PdcaMetricService.Resolved stockout = metricService.resolve(PdcaMetricService.K_STOCKOUT_RATE, null);
        PdcaMetricService.Resolved takeback = metricService.resolve(PdcaMetricService.K_TAKEBACK_RATE, null);
        ind(replenish, "售罄货道占比", stockout, "%", STOCKOUT_AMBER, STOCKOUT_RED, false);
        ind(replenish, "平均带回率", takeback, "%", TAKEBACK_AMBER, TAKEBACK_RED, false);
        replenish.setHint("售罄多→提服务水平/加货道;带回率高→降配货量(AI 补货页调参)");
        resp.getScenes().add(replenish);

        // ② 采购:实收差异单占比 + 最大进价环比涨幅
        PdcaDtos.BoardScene purchase = scene(ActionItem.SCENE_PURCHASE, "📥", "实收差异 0 · 进价环比 ≤0");
        PdcaMetricService.Resolved receiptDiff = metricService.resolve(PdcaMetricService.K_RECEIPT_DIFF_RATE, null);
        // 实收差异:目标 0,有一单差异就该黄,一半以上红
        ind(purchase, "实收差异单占比", receiptDiff, "%", new BigDecimal("0"), new BigDecimal("50"), false);
        Object[] maxRise = maxPriceRise(period);
        PdcaDtos.BoardIndicator riseInd = new PdcaDtos.BoardIndicator();
        riseInd.setLabel("最大进价环比");
        if (maxRise == null) {
            riseInd.setDisplay("—(本月无可比进价)");
            riseInd.setLevel("gray");
        } else {
            BigDecimal pct = (BigDecimal) maxRise[1];
            riseInd.setDisplay((pct.signum() > 0 ? "+" : "") + pct + "%(商品#" + maxRise[0] + ")");
            riseInd.setLevel(pct.compareTo(PRICE_RISE_RED) > 0 ? "red"
                    : pct.signum() > 0 ? "amber" : "green");
        }
        purchase.getIndicators().add(riseInd);
        purchase.setHint("实收差→找供应商补货款;涨价→比价页找第二供应商/议价");
        resp.getScenes().add(purchase);

        // ③ 选品:动销率(sale_record 简版;M4-1 BI 多维口径接位)
        PdcaDtos.BoardScene select = scene(ActionItem.SCENE_SELECT, "🧃", "动销率 ≥75%");
        PdcaMetricService.Resolved sellThrough = metricService.resolve(PdcaMetricService.K_SELL_THROUGH, null);
        ind(select, "当月动销率", sellThrough, "%", SELL_THROUGH_AMBER, SELL_THROUGH_RED, true);
        select.setHint("动销低→报表中心看滞销名单,淘汰/换货道;BI 页(M4-1)有四象限");
        resp.getScenes().add(select);

        // ④ 定价:本月调价次数 + 最近调价效果(有调价才有 A/B 对照)
        PdcaDtos.BoardScene price = scene(ActionItem.SCENE_PRICE, "🏷", "调价必对照前后 14 天");
        long changes = queryMapper.monthPriceChangeCount(period);
        PdcaDtos.BoardIndicator changeInd = new PdcaDtos.BoardIndicator();
        changeInd.setLabel("本月调价次数");
        changeInd.setDisplay(changes + " 次");
        changeInd.setLevel(changes == 0 ? "green" : "amber");
        price.getIndicators().add(changeInd);
        price.setLight(changes == 0 ? "green" : "amber");
        price.setHint(changes == 0 ? "本月无调价,无异动"
                : "有调价:登记改进任务用「调价后销量变化」指标做 14 天 A/B 回查");
        resp.getScenes().add(price);

        // ⑤ 钱账:复用驾驶舱四灯(M3 aging 单一真相源,不另抄公式)
        PdcaDtos.BoardScene money = scene(ActionItem.SCENE_MONEY, "💰", "无逾期应付 · 无差异挂起 · 无索赔/结算超期");
        MoneyLightsService.MoneyLightsResp lights = moneyLightsService.lights();
        PdcaDtos.BoardIndicator moneyInd = new PdcaDtos.BoardIndicator();
        moneyInd.setLabel("钱账红灯数");
        moneyInd.setDisplay(lights.getRedTotal() + " 盏(逾期应付 " + lights.getOverduePayableCount()
                + " · 差异挂起 " + lights.getDiffTotal() + " · 索赔超期 " + lights.getClaimOverdueCount()
                + (lights.isSettleOverdue() ? " · 在途超期 1" : "") + ")");
        moneyInd.setLevel(lights.getRedTotal() > 0 ? "red" : "green");
        money.getIndicators().add(moneyInd);
        money.setLight(lights.getRedTotal() > 0 ? "red" : "green");
        money.setHint("红灯明细在驾驶舱红灯区,点灯直达对应页处理");
        resp.getScenes().add(money);

        // ⑥ 盘点:当月损耗额 + 账实符合率
        PdcaDtos.BoardScene stocktake = scene(ActionItem.SCENE_STOCKTAKE, "📋", "损耗 <¥30/月 · 符合率 ≥98%");
        PdcaMetricService.Resolved loss = metricService.resolve(PdcaMetricService.K_LOSS_AMOUNT, null);
        PdcaMetricService.Resolved match = metricService.resolve(PdcaMetricService.K_MATCH_RATE, null);
        ind(stocktake, "当月损耗额", loss, "¥", LOSS_AMBER, LOSS_RED, false);
        ind(stocktake, "账实符合率", match, "%", MATCH_AMBER, new BigDecimal("90"), true);
        stocktake.setHint("损耗超线→盘点页五步向导一键起草改进任务(按原因)");
        resp.getScenes().add(stocktake);

        // 各环节进行中的任务数 + 到期数
        for (PdcaDtos.BoardScene s : resp.getScenes()) {
            s.setOpenItems(itemMapper.selectCount(new LambdaQueryWrapper<ActionItem>()
                    .eq(ActionItem::getSourceScene, s.getScene())
                    .in(ActionItem::getItemStatus, ActionItem.ST_OPEN, ActionItem.ST_ESCALATED)));
        }
        resp.setDueCount(itemMapper.selectCount(new LambdaQueryWrapper<ActionItem>()
                .eq(ActionItem::getItemStatus, ActionItem.ST_OPEN)
                .le(ActionItem::getVerifyDate, LocalDate.now())));
        return resp;
    }

    // ============================== 内部 ==============================

    private static PdcaDtos.BoardScene scene(String name, String icon, String target) {
        PdcaDtos.BoardScene s = new PdcaDtos.BoardScene();
        s.setScene(name);
        s.setIcon(icon);
        s.setTarget(target);
        s.setLight("gray");
        return s;
    }

    /**
     * 加一个指标并按阈值定级:
     * higherBetter=false → 值超 amber 黄、超 red 红;higherBetter=true → 值低于 amber 黄、低于 red 红。
     * 环节灯 = 该环节所有指标里最差的一盏(红>黄>绿>灰)。
     */
    private static void ind(PdcaDtos.BoardScene scene, String label, PdcaMetricService.Resolved r,
                            String unit, BigDecimal amber, BigDecimal red, boolean higherBetter) {
        PdcaDtos.BoardIndicator i = new PdcaDtos.BoardIndicator();
        i.setLabel(label);
        if (r.value == null) {
            i.setDisplay("—(" + r.note + ")");
            i.setLevel("gray");
        } else {
            i.setDisplay(("¥".equals(unit) ? "¥" : "") + r.value.stripTrailingZeros().toPlainString()
                    + ("%".equals(unit) ? "%" : ""));
            String level;
            if (higherBetter) {
                level = r.value.compareTo(red) < 0 ? "red" : r.value.compareTo(amber) < 0 ? "amber" : "green";
            } else {
                level = r.value.compareTo(red) > 0 ? "red" : r.value.compareTo(amber) > 0 ? "amber" : "green";
            }
            i.setLevel(level);
        }
        scene.getIndicators().add(i);
        scene.setLight(worst(scene.getLight(), i.getLevel()));
    }

    private static final List<String> ORDER = Arrays.asList("gray", "green", "amber", "red");

    private static String worst(String a, String b) {
        return ORDER.indexOf(b) > ORDER.indexOf(a) ? b : a;
    }

    /** 本月有采购的商品里,最近价 vs 上一次价 的最大涨幅(无可比返回 null;[productId, pct]) */
    private Object[] maxPriceRise(String period) {
        List<java.util.Map<String, Object>> items = queryMapper.purchaseItemsSince(LocalDate.now().minusMonths(6));
        // product → 按时间倒序的价格序列(SQL 已排好序)
        java.util.Map<Long, List<Object[]>> byProduct = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> row : items) {
            Long pid = ((Number) row.get("productId")).longValue();
            byProduct.computeIfAbsent(pid, k -> new java.util.ArrayList<>())
                    .add(new Object[]{new BigDecimal(row.get("unitPrice").toString()),
                            row.get("bizDate").toString()});
        }
        Object[] best = null;
        for (java.util.Map.Entry<Long, List<Object[]>> e : byProduct.entrySet()) {
            List<Object[]> seq = e.getValue();
            // 只看"本月买过"的商品(最新一条落在本月)
            if (seq.size() < 2 || !seq.get(0)[1].toString().startsWith(period)) {
                continue;
            }
            BigDecimal last = (BigDecimal) seq.get(0)[0];
            BigDecimal prev = (BigDecimal) seq.get(1)[0];
            if (prev.signum() == 0) {
                continue;
            }
            BigDecimal pct = last.subtract(prev).multiply(new BigDecimal("100"))
                    .divide(prev, 1, java.math.RoundingMode.HALF_UP);
            if (best == null || pct.compareTo((BigDecimal) best[1]) > 0) {
                best = new Object[]{e.getKey(), pct};
            }
        }
        return best;
    }
}
