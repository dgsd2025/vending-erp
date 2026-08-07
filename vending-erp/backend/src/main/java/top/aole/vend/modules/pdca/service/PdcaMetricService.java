package top.aole.vend.modules.pdca.service;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.pdca.dto.PdcaDtos;
import top.aole.vend.modules.pdca.mapper.PdcaQueryMapper;
import top.aole.vend.modules.report.service.MoneyLightsService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * C 指标注册表(§8.4:到期自动回查的取数口):每个指标键一个取数函数,
 * 全部对接既有真数据源(盘点损耗/带回率/实收差异/进价/动销/调价效果/钱账灯),
 * 取不到数一律返回 null → 回查判定走"需人工判定"分支,绝不编数字。
 *
 * 铁律#7:规则引擎出数字——本表所有值都是 SQL/Java 算的,LLM 只起草措施不出数。
 * 选品动销率为 sale_record 简版口径;BI 票(M4-1)的多维口径上线后此键换数据源,键名不变。
 */
@Service
@RequiredArgsConstructor
public class PdcaMetricService {

    // ---------- 指标键(改进任务 metric_key 落这些值) ----------
    public static final String K_LOSS_AMOUNT = "stocktake.loss_amount";
    public static final String K_MATCH_RATE = "stocktake.match_rate";
    public static final String K_STOCKOUT_RATE = "replenish.stockout_rate";
    public static final String K_TAKEBACK_RATE = "replenish.takeback_rate";
    public static final String K_RECEIPT_DIFF_RATE = "purchase.receipt_diff_rate";
    public static final String K_PRICE_LAST = "purchase.price_last";
    public static final String K_PRICE_MOM = "purchase.price_mom";
    public static final String K_SELL_THROUGH = "select.sell_through_rate";
    public static final String K_PRICE_EFFECT = "price.change_effect";
    public static final String K_OVERDUE_PAYABLE = "money.overdue_payable_count";
    public static final String K_DIFF_PENDING = "money.diff_pending_count";

    /** 调价前后对比窗口(§8.3 定价 C:调价前后 14 天 A/B 自对照) */
    public static final int PRICE_EFFECT_WINDOW_DAYS = 14;

    private final PdcaQueryMapper queryMapper;
    private final MoneyLightsService moneyLightsService;

    /** 指标定义:名字/单位/参数提示/推荐方向/取数函数(param → 值,null=取不到) */
    public static class MetricDef {
        public final String key;
        public final String name;
        public final String unit;
        public final String paramHint;
        public final String defaultOp;
        public final Function<String, Resolved> resolver;

        MetricDef(String key, String name, String unit, String paramHint, String defaultOp,
                  Function<String, Resolved> resolver) {
            this.key = key;
            this.name = name;
            this.unit = unit;
            this.paramHint = paramHint;
            this.defaultOp = defaultOp;
            this.resolver = resolver;
        }
    }

    /** 取数结果:value=null 即"需人工判定",note 说明为什么 */
    public static class Resolved {
        public final BigDecimal value;
        public final String note;

        public Resolved(BigDecimal value, String note) {
            this.value = value;
            this.note = note;
        }

        static Resolved of(BigDecimal v, String note) {
            return new Resolved(v, note);
        }

        static Resolved manual(String why) {
            return new Resolved(null, why);
        }
    }

    private Map<String, MetricDef> registry;

    private synchronized Map<String, MetricDef> registry() {
        if (registry == null) {
            Map<String, MetricDef> m = new LinkedHashMap<>();
            reg(m, K_LOSS_AMOUNT, "当月损耗额", "¥", "损耗原因(空=全部,如:过期报损/吞货掉货/被盗)", "<=",
                    this::lossAmount);
            reg(m, K_MATCH_RATE, "当月账实符合率", "%", null, ">=", p -> matchRate());
            reg(m, K_STOCKOUT_RATE, "当前售罄货道占比", "%", null, "<=", p -> stockoutRate());
            reg(m, K_TAKEBACK_RATE, "当月平均带回率", "%", null, "<=", p -> takebackRate());
            reg(m, K_RECEIPT_DIFF_RATE, "当月实收差异单占比", "%", null, "<=", p -> receiptDiffRate());
            reg(m, K_PRICE_LAST, "最近落账进价", "¥", "商品ID", "<=", this::priceLast);
            reg(m, K_PRICE_MOM, "最近进价环比", "%", "商品ID", "<=", this::priceMom);
            reg(m, K_SELL_THROUGH, "当月动销率", "%", null, ">=", p -> sellThrough());
            reg(m, K_PRICE_EFFECT, "调价后销量变化", "%", "商品ID", ">=", this::priceEffect);
            reg(m, K_OVERDUE_PAYABLE, "逾期应付家数", "家", null, "<=", p -> moneyOverdue());
            reg(m, K_DIFF_PENDING, "差异挂起单数", "张", null, "<=", p -> moneyDiff());
            registry = m;
        }
        return registry;
    }

    private void reg(Map<String, MetricDef> m, String key, String name, String unit,
                     String paramHint, String op, Function<String, Resolved> fn) {
        m.put(key, new MetricDef(key, name, unit, paramHint, op, fn));
    }

    /** 注册表清单(前端下拉) */
    public List<PdcaDtos.MetricDefRow> listDefs() {
        List<PdcaDtos.MetricDefRow> rows = new ArrayList<>();
        for (MetricDef def : registry().values()) {
            PdcaDtos.MetricDefRow row = new PdcaDtos.MetricDefRow();
            row.setKey(def.key);
            row.setName(def.name);
            row.setUnit(def.unit);
            row.setParamHint(def.paramHint);
            row.setDefaultOp(def.defaultOp);
            rows.add(row);
        }
        return rows;
    }

    public MetricDef def(String key) {
        return key == null ? null : registry().get(key);
    }

    /**
     * 取指标当前值:键不存在/未注册 → value=null(需人工判定)。
     * 到期自动回查、看板红绿灯、新建任务的基线值都走这一个口。
     */
    public Resolved resolve(String key, String param) {
        MetricDef def = def(key);
        if (def == null) {
            return Resolved.manual(StrUtil.isBlank(key) ? "未设指标键,需人工判定"
                    : "指标键未注册:" + key + ",需人工判定");
        }
        return def.resolver.apply(StrUtil.blankToDefault(param, null));
    }

    /** 本月期(yyyy-MM) */
    public String currentPeriod() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    // ============================== 各指标取数 ==============================

    /** 盘点:当月(可按原因)损耗额;当月无已完成盘点 → 需人工(防"没盘=0损耗"误通过) */
    private Resolved lossAmount(String reason) {
        String period = currentPeriod();
        if (queryMapper.monthStocktakeCount(period) == 0) {
            return Resolved.manual("本月(" + period + ")尚无已完成盘点,损耗额无从谈起——先盘点再回查");
        }
        BigDecimal v = nz(queryMapper.monthLossAmount(period, reason)).setScale(2, RoundingMode.HALF_UP);
        return Resolved.of(v, "本月" + (reason == null ? "全部原因" : "「" + reason + "」") + "损耗额");
    }

    /** 盘点:当月账实符合率% */
    private Resolved matchRate() {
        String period = currentPeriod();
        Map<String, Object> row = queryMapper.monthMatchRate(period);
        long total = num(row == null ? null : row.get("totalRows"));
        if (total == 0) {
            return Resolved.manual("本月尚无已完成盘点,符合率无法计算");
        }
        long diff = num(row.get("diffRows"));
        BigDecimal v = BigDecimal.valueOf(total - diff)
                .multiply(HUNDRED).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
        return Resolved.of(v, "本月盘点 " + total + " 行中 " + diff + " 行有差异");
    }

    /** 补货:当前售罄货道占比%(current_qty 推算值口径) */
    private Resolved stockoutRate() {
        Map<String, Object> row = queryMapper.stockoutSlots();
        long total = num(row == null ? null : row.get("totalSlots"));
        if (total == 0) {
            return Resolved.manual("尚无已绑商品的货道");
        }
        long out = num(row.get("outSlots"));
        BigDecimal v = BigDecimal.valueOf(out)
                .multiply(HUNDRED).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
        return Resolved.of(v, out + "/" + total + " 条货道售罄(推算账口径,权威以后台缺货页为准)");
    }

    /** 补货:当月平均带回率%(prekit 核销时算好的 takeback_rate 月均) */
    private Resolved takebackRate() {
        BigDecimal avg = queryMapper.monthTakebackAvg(currentPeriod());
        if (avg == null) {
            return Resolved.manual("本月尚无已核销配货单,带回率算不出——先去出库上架页核销");
        }
        return Resolved.of(avg.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP), "本月已核销配货单平均带回率");
    }

    /** 采购:当月实收差异单占比% */
    private Resolved receiptDiffRate() {
        Map<String, Object> row = queryMapper.monthReceiptDiff(currentPeriod());
        long total = num(row == null ? null : row.get("totalReceipts"));
        if (total == 0) {
            return Resolved.manual("本月尚无落账采购入库单");
        }
        long diff = num(row.get("diffReceipts"));
        BigDecimal v = BigDecimal.valueOf(diff)
                .multiply(HUNDRED).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
        return Resolved.of(v, "本月 " + total + " 张入库单中 " + diff + " 张实收≠应收");
    }

    /** 采购:某商品最近落账进价(支持"景田 ≤¥16/箱"这类目标) */
    private Resolved priceLast(String param) {
        Long productId = parseId(param);
        if (productId == null) {
            return Resolved.manual("指标参数需为商品ID");
        }
        List<BigDecimal> prices = lastTwoPrices(productId);
        if (prices.isEmpty()) {
            return Resolved.manual("该商品尚无落账采购记录");
        }
        return Resolved.of(prices.get(0), "最近一次落账进价");
    }

    /** 采购:某商品最近进价环比%(最近价 vs 上一次价) */
    private Resolved priceMom(String param) {
        Long productId = parseId(param);
        if (productId == null) {
            return Resolved.manual("指标参数需为商品ID");
        }
        List<BigDecimal> prices = lastTwoPrices(productId);
        if (prices.size() < 2) {
            return Resolved.manual("该商品落账采购不足 2 次,环比算不出");
        }
        BigDecimal last = prices.get(0);
        BigDecimal prev = prices.get(1);
        if (prev.signum() == 0) {
            return Resolved.manual("上一次进价为 0,环比无意义");
        }
        BigDecimal v = last.subtract(prev).multiply(HUNDRED).divide(prev, 1, RoundingMode.HALF_UP);
        return Resolved.of(v, "最近 ¥" + last + " vs 上次 ¥" + prev);
    }

    /** 选品:当月动销率%(sale_record 简版;M4-1 BI 多维口径上线后换源,键不变) */
    private Resolved sellThrough() {
        long onSale = queryMapper.onSaleProductCount();
        if (onSale == 0) {
            return Resolved.manual("尚无在售商品");
        }
        long sold = queryMapper.monthSoldSkuCount(currentPeriod());
        BigDecimal v = BigDecimal.valueOf(sold)
                .multiply(HUNDRED).divide(BigDecimal.valueOf(onSale), 1, RoundingMode.HALF_UP);
        return Resolved.of(v, "本月 " + onSale + " 个在售 SKU 中 " + sold + " 个有动销");
    }

    /** 定价:某商品最近一次调价后 14 天日均销量 vs 前 14 天 变化%(A/B 自对照) */
    private Resolved priceEffect(String param) {
        Long productId = parseId(param);
        if (productId == null) {
            return Resolved.manual("指标参数需为商品ID");
        }
        Map<String, Object> change = queryMapper.lastPriceChange(productId);
        if (change == null || change.get("effectDate") == null) {
            return Resolved.manual("该商品无改价记录,无对照可比");
        }
        LocalDate effect = LocalDate.parse(change.get("effectDate").toString());
        LocalDateTime pivot = effect.atStartOfDay();
        // 调价后窗口:生效日起,最多 14 天,截止不超过今天(不足 14 天按实际天数折日均)
        long afterDays = Math.min(PRICE_EFFECT_WINDOW_DAYS,
                java.time.temporal.ChronoUnit.DAYS.between(effect, LocalDate.now().plusDays(1)));
        if (afterDays < 3) {
            return Resolved.manual("调价刚生效 " + afterDays + " 天,样本太短(<3 天)——过几天再回查");
        }
        BigDecimal before = queryMapper.salesQtyBetween(productId,
                pivot.minusDays(PRICE_EFFECT_WINDOW_DAYS), pivot);
        BigDecimal after = queryMapper.salesQtyBetween(productId, pivot, pivot.plusDays(afterDays));
        BigDecimal beforeAvg = before.divide(BigDecimal.valueOf(PRICE_EFFECT_WINDOW_DAYS), 4, RoundingMode.HALF_UP);
        BigDecimal afterAvg = after.divide(BigDecimal.valueOf(afterDays), 4, RoundingMode.HALF_UP);
        if (beforeAvg.signum() == 0) {
            return Resolved.manual("调价前 14 天没卖过,变化率无从算——需人工看");
        }
        BigDecimal v = afterAvg.subtract(beforeAvg).multiply(HUNDRED).divide(beforeAvg, 1, RoundingMode.HALF_UP);
        return Resolved.of(v, "调价(" + effect + ")后日均 " + afterAvg.stripTrailingZeros().toPlainString()
                + " vs 前 " + beforeAvg.stripTrailingZeros().toPlainString());
    }

    /** 钱账:逾期应付家数(复用 M3 aging 灯口径) */
    private Resolved moneyOverdue() {
        return Resolved.of(BigDecimal.valueOf(moneyLightsService.lights().getOverduePayableCount()),
                "供应商应付逾期家数(与驾驶舱红灯同尺)");
    }

    /** 钱账:差异挂起单数(结算单+付款+平台结算三表,复用驾驶舱灯口径) */
    private Resolved moneyDiff() {
        return Resolved.of(BigDecimal.valueOf(moneyLightsService.lights().getDiffTotal()),
                "差异挂起单数(结算/付款/平台结算三表合计)");
    }

    // ============================== 小工具 ==============================

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 某商品最近两次落账进价(去重连续同价不去,按时间序取最近两条) */
    private List<BigDecimal> lastTwoPrices(Long productId) {
        List<BigDecimal> result = new ArrayList<>();
        for (Map<String, Object> row : queryMapper.purchaseItemsSince(LocalDate.now().minusMonths(6))) {
            if (productId.equals(num2long(row.get("productId")))) {
                result.add(new BigDecimal(row.get("unitPrice").toString()));
                if (result.size() >= 2) {
                    break;
                }
            }
        }
        return result;
    }

    private static Long parseId(String param) {
        if (StrUtil.isBlank(param)) {
            return null;
        }
        try {
            return Long.parseLong(param.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static Long num2long(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
