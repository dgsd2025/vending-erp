package top.aole.vend.modules.bi.service;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.bi.dto.BiDtos;
import top.aole.vend.modules.bi.dto.BiDtos.MatrixResp;
import top.aole.vend.modules.bi.dto.BiDtos.MatrixRow;
import top.aole.vend.modules.bi.mapper.BiQueryMapper;
import top.aole.vend.modules.bi.mapper.BiQueryMapper.BiLossRow;
import top.aole.vend.modules.bi.mapper.BiQueryMapper.BiMachineLedgerRow;
import top.aole.vend.modules.bi.mapper.BiQueryMapper.BiSaleRow;
import top.aole.vend.modules.bi.mapper.BiQueryMapper.BiSlotRow;
import top.aole.vend.modules.bi.mapper.BiQueryMapper.BiSnapshotRow;
import top.aole.vend.modules.replenish.domain.DemandStats;
import top.aole.vend.modules.replenish.service.DemandStatsService;
import top.aole.vend.modules.report.mapper.ReportQueryMapper;
import top.aole.vend.modules.report.service.CostEngine;
import top.aole.vend.modules.report.service.CostEngine.Replay;
import top.aole.vend.modules.stock.service.StockService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * BI 经营分析(M4-1,§10.1):六维矩阵 + 单品四象限 + 客单/连带/支付 + 缺货损失估算 + 调价对比。
 *
 * 只读模块,零写接口。取数全部复用现成生产者:
 * - 成本/毛利:report.CostEngine 事件重放(§13 口径,禁重造);
 * - 日均/存销比:replenish.DemandStatsService(28 天滚动,与补货同口径);
 * - 当前库存:stock.StockService(锚点+增量);
 * - 损耗:stocktake 已完成盘亏行;带回率产地在 prekit(此处不重复算)。
 *
 * 矩阵铁律:取不到的格子 = null(前端显「—」),禁造假数。
 */
@Service
@RequiredArgsConstructor
public class BiService {

    public static final String DIM_MACHINE = "machine";
    public static final String DIM_CATEGORY = "category";
    public static final String DIM_PRODUCT = "product";
    public static final String DIM_SLOT = "slot";
    public static final String DIM_TIMESLOT = "timeslot";
    public static final String DIM_SUPPLIER = "supplier";

    private static final String UNCATEGORIZED = "未分类";

    private final BiQueryMapper biQueryMapper;
    private final CostEngine costEngine;
    private final ReportQueryMapper reportQueryMapper;
    private final DemandStatsService demandStatsService;
    private final StockService stockService;
    private final ProductMapper productMapper;
    private final MachineMapper machineMapper;
    private final SupplierMapper supplierMapper;

    // ============================== 通用上下文 ==============================

    /** 一次请求的公共取数(销售行 + 成本重放 + 月份序列 + 水印) */
    private class Ctx {
        final Replay replay;
        final List<BiSaleRow> sales;
        final LocalDateTime dataAsOf;
        final List<String> months;
        final String month;
        final YearMonth ym;
        /** 本月已过天数(dataAsOf 在月内截到 dataAsOf;整月已过 = 月天数;月份在未来 → 0) */
        final int daysElapsed;

        Ctx(String monthParam) {
            replay = costEngine.replay();
            sales = biQueryMapper.saleRows();
            dataAsOf = reportQueryMapper.dataAsOf();
            months = replay.getMonths();
            if (months.isEmpty()) {
                month = monthParam;
                ym = null;
                daysElapsed = 0;
                return;
            }
            month = StrUtil.isBlank(monthParam) ? months.get(months.size() - 1) : monthParam;
            ym = YearMonth.parse(month);
            LocalDate monthEnd = ym.atEndOfMonth();
            LocalDate asOfDay = dataAsOf == null ? monthEnd : dataAsOf.toLocalDate();
            if (asOfDay.isBefore(ym.atDay(1))) {
                daysElapsed = 0;
            } else {
                LocalDate end = asOfDay.isBefore(monthEnd) ? asOfDay : monthEnd;
                daysElapsed = end.getDayOfMonth();
            }
        }

        boolean inMonth(BiSaleRow s) {
            return month != null && month.equals(s.getBizPeriod());
        }
    }

    /** 单行销售的毛利口径拆解(§13):测试跳过;兑换/线下收入 0;退款收入原样(负)、销量取负 */
    private static class SaleView {
        BigDecimal amt;
        BigDecimal qtySigned;
        BigDecimal cost;      // null = 无成本(未绑定/无采购史)
        boolean noCost;
    }

    private SaleView view(Ctx ctx, BiSaleRow s) {
        String type = s.getOrderType() == null ? "正常" : s.getOrderType();
        if ("测试".equals(type)) {
            return null;
        }
        SaleView v = new SaleView();
        boolean refund = "退款".equals(type);
        boolean zeroRevenue = "兑换".equals(type) || "线下补录".equals(type);
        v.amt = zeroRevenue ? BigDecimal.ZERO : nvl(s.getAmountReceived());
        v.qtySigned = refund ? nvl(s.getQty()).negate() : nvl(s.getQty());
        v.cost = ctx.replay.getSaleCost().get(s.getId());
        v.noCost = v.cost == null;
        return v;
    }

    // ============================== 六维矩阵 ==============================

    public MatrixResp matrix(String month, String dim) {
        Ctx ctx = new Ctx(month);
        MatrixResp resp = new MatrixResp();
        resp.setMonth(ctx.month);
        resp.setMonths(ctx.months);
        resp.setDim(dim);
        resp.setDataAsOf(ctx.dataAsOf);
        resp.setDaysElapsed(ctx.daysElapsed);
        if (ctx.ym == null) {
            return resp;
        }
        switch (dim == null ? DIM_MACHINE : dim) {
            case DIM_MACHINE:
                machineDim(ctx, resp);
                break;
            case DIM_CATEGORY:
                categoryDim(ctx, resp);
                break;
            case DIM_PRODUCT:
                productDim(ctx, resp);
                break;
            case DIM_SLOT:
                slotDim(ctx, resp);
                break;
            case DIM_TIMESLOT:
                timeslotDim(ctx, resp);
                break;
            case DIM_SUPPLIER:
                supplierDim(ctx, resp);
                break;
            default:
                throw new BizException("未知维度:" + dim);
        }
        return resp;
    }

    /** 聚合桶:销售/毛利公共累加器 */
    private static class Agg {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal amt = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal costedAmt = BigDecimal.ZERO;
        Set<Long> noCostSkus = new LinkedHashSet<>();
        Set<Long> soldSkus = new LinkedHashSet<>();

        void add(BiSaleRow s, SaleView v) {
            qty = qty.add(v.qtySigned);
            amt = amt.add(v.amt);
            if (v.cost != null) {
                cost = cost.add(v.cost);
                costedAmt = costedAmt.add(v.amt);
            } else if (s.getProductId() != null) {
                noCostSkus.add(s.getProductId());
            }
            if (s.getProductId() != null && v.qtySigned.signum() > 0) {
                soldSkus.add(s.getProductId());
            }
        }

        BigDecimal gross() {
            return amt.subtract(cost);
        }

        /** 毛利率 =(已计成本口径)毛利 ÷ 有成本部分销售额;分母 0 → null */
        BigDecimal marginPct() {
            if (costedAmt.signum() == 0) {
                return null;
            }
            return costedAmt.subtract(cost).multiply(BigDecimal.valueOf(100))
                    .divide(costedAmt, 2, RoundingMode.HALF_UP);
        }
    }

    private void machineDim(Ctx ctx, MatrixResp resp) {
        Map<Long, Agg> byMachine = new LinkedHashMap<>();
        for (BiSaleRow s : ctx.sales) {
            if (!ctx.inMonth(s) || s.getMachineId() == null) {
                continue;
            }
            SaleView v = view(ctx, s);
            if (v == null) {
                continue;
            }
            byMachine.computeIfAbsent(s.getMachineId(), k -> new Agg()).add(s, v);
        }
        Map<Long, Machine> machines = loadMachines();
        // 损耗:月×机器
        Map<Long, BigDecimal[]> loss = new HashMap<>();
        for (BiLossRow l : biQueryMapper.lossRows()) {
            if (ctx.month.equals(l.getMonth()) && l.getMachineId() != null) {
                BigDecimal[] cell = loss.computeIfAbsent(l.getMachineId(),
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                cell[0] = cell[0].add(nvl(l.getQty()));
                cell[1] = cell[1].add(nvl(l.getAmount()));
            }
        }
        // 存销比:机内现存Σ ÷ 28 天日均Σ(与补货同口径,是"当前"值)
        DemandStatsService.Snapshot demand = demandStatsService.compute();
        // 缺货损失(估算):逐机器汇总
        Map<Long, BigDecimal> stockoutLoss = new HashMap<>();
        for (BiDtos.StockoutLossRow r : stockoutLoss(ctx.month).getRows()) {
            stockoutLoss.merge(r.getMachineId(), nvl(r.getEstLoss()), BigDecimal::add);
        }
        for (Map.Entry<Long, Agg> e : byMachine.entrySet()) {
            Machine m = machines.get(e.getKey());
            Agg a = e.getValue();
            MatrixRow row = new MatrixRow();
            row.setKey(e.getKey());
            row.setName(m == null ? "机器#" + e.getKey() : m.getMachineName());
            row.setCode(m == null ? null : m.getMachineCode());
            row.setSalesQty(a.qty);
            row.setSalesAmt(scale2(a.amt));
            if (ctx.daysElapsed > 0) {
                row.setDailyAvgAmt(a.amt.divide(BigDecimal.valueOf(ctx.daysElapsed), 2, RoundingMode.HALF_UP));
            }
            row.setGrossProfit(scale2(a.gross()));
            row.setMarginPct(a.marginPct());
            row.setNoCostSkuCount(a.noCostSkus.size());
            // 存销比:机内现存 ÷ 日均
            Map<Long, BigDecimal> machineStock = stockService.getMachineStockAll(e.getKey());
            BigDecimal stockSum = BigDecimal.ZERO;
            for (BigDecimal q : machineStock.values()) {
                if (q != null && q.signum() > 0) {
                    stockSum = stockSum.add(q);
                }
            }
            BigDecimal dailySum = BigDecimal.ZERO;
            Map<Long, DemandStats> perMachine = demand.byMachine.get(e.getKey());
            if (perMachine != null) {
                for (DemandStats ds : perMachine.values()) {
                    dailySum = dailySum.add(nvl(ds.getAvgDaily()));
                }
            }
            if (dailySum.signum() > 0) {
                row.setStockDays(stockSum.divide(dailySum, 1, RoundingMode.HALF_UP));
            }
            BigDecimal[] lossCell = loss.get(e.getKey());
            if (lossCell != null) {
                row.setLossQty(lossCell[0]);
                row.setLossAmt(scale2(lossCell[1]));
            }
            row.setStockoutLossAmt(stockoutLoss.get(e.getKey()));
            resp.getRows().add(row);
        }
        resp.getRows().sort(bySalesDesc());
    }

    private void categoryDim(Ctx ctx, MatrixResp resp) {
        Map<Long, Product> products = loadProducts();
        Map<String, Agg> byCat = new LinkedHashMap<>();
        for (BiSaleRow s : ctx.sales) {
            if (!ctx.inMonth(s)) {
                continue;
            }
            SaleView v = view(ctx, s);
            if (v == null) {
                continue;
            }
            byCat.computeIfAbsent(category(products, s.getProductId()), k -> new Agg()).add(s, v);
        }
        // 在售 SKU 数(动销率分母)
        Map<String, Integer> onSale = new HashMap<>();
        for (Product p : products.values()) {
            if ("在售".equals(p.getProductStatus())) {
                onSale.merge(category(p), 1, Integer::sum);
            }
        }
        // 损耗:月×品类
        Map<String, BigDecimal[]> loss = new HashMap<>();
        for (BiLossRow l : biQueryMapper.lossRows()) {
            if (ctx.month.equals(l.getMonth())) {
                String cat = category(products, l.getProductId());
                BigDecimal[] cell = loss.computeIfAbsent(cat,
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                cell[0] = cell[0].add(nvl(l.getQty()));
                cell[1] = cell[1].add(nvl(l.getAmount()));
            }
        }
        BigDecimal totalAmt = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;
        for (Agg a : byCat.values()) {
            totalAmt = totalAmt.add(a.amt);
            totalGross = totalGross.add(a.gross());
        }
        for (Map.Entry<String, Agg> e : byCat.entrySet()) {
            Agg a = e.getValue();
            MatrixRow row = new MatrixRow();
            row.setName(e.getKey());
            row.setCategory(e.getKey());
            row.setSalesQty(a.qty);
            row.setSalesAmt(scale2(a.amt));
            row.setSharePct(pct(a.amt, totalAmt));
            row.setGrossProfit(scale2(a.gross()));
            row.setGrossSharePct(pct(a.gross(), totalGross));
            row.setMarginPct(a.marginPct());
            row.setNoCostSkuCount(a.noCostSkus.size());
            Integer denom = onSale.get(e.getKey());
            row.setSoldSkuCount(a.soldSkus.size());
            row.setOnSaleSkuCount(denom);
            if (denom != null && denom > 0) {
                row.setActiveRate(BigDecimal.valueOf(a.soldSkus.size() * 100L)
                        .divide(BigDecimal.valueOf(denom), 1, RoundingMode.HALF_UP));
            }
            BigDecimal[] lossCell = loss.get(e.getKey());
            if (lossCell != null) {
                row.setLossQty(lossCell[0]);
                row.setLossAmt(scale2(lossCell[1]));
            }
            resp.getRows().add(row);
        }
        resp.getRows().sort(bySalesDesc());
    }

    private void productDim(Ctx ctx, MatrixResp resp) {
        Map<Long, Product> products = loadProducts();
        Map<Long, Agg> byProduct = new LinkedHashMap<>();
        for (BiSaleRow s : ctx.sales) {
            if (!ctx.inMonth(s) || s.getProductId() == null) {
                continue;
            }
            SaleView v = view(ctx, s);
            if (v == null) {
                continue;
            }
            byProduct.computeIfAbsent(s.getProductId(), k -> new Agg()).add(s, v);
        }
        Map<Long, BigDecimal[]> loss = new HashMap<>();
        for (BiLossRow l : biQueryMapper.lossRows()) {
            if (ctx.month.equals(l.getMonth()) && l.getProductId() != null) {
                BigDecimal[] cell = loss.computeIfAbsent(l.getProductId(),
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                cell[0] = cell[0].add(nvl(l.getQty()));
                cell[1] = cell[1].add(nvl(l.getAmount()));
            }
        }
        DemandStatsService.Snapshot demand = demandStatsService.compute();
        // 当前总库存(仓库+机器):周转天数分子
        List<Long> ids = new ArrayList<>(byProduct.keySet());
        Map<Long, BigDecimal> warehouse = ids.isEmpty() ? Collections.emptyMap()
                : stockService.getWarehouseStockBatch(ids);
        Map<Long, BigDecimal> machineTotal = new HashMap<>();
        for (Machine m : loadMachines().values()) {
            for (Map.Entry<Long, BigDecimal> e : stockService.getMachineStockAll(m.getId()).entrySet()) {
                machineTotal.merge(e.getKey(), nvl(e.getValue()), BigDecimal::add);
            }
        }
        for (Map.Entry<Long, Agg> e : byProduct.entrySet()) {
            Product p = products.get(e.getKey());
            Agg a = e.getValue();
            MatrixRow row = new MatrixRow();
            row.setKey(e.getKey());
            row.setName(p == null ? "SKU#" + e.getKey() : p.getProductName());
            row.setCode(p == null ? null : p.getSkuCode());
            row.setCategory(p == null ? null : p.getCategory());
            row.setSalesQty(a.qty);
            row.setSalesAmt(scale2(a.amt));
            boolean hasCost = a.noCostSkus.isEmpty();
            row.setHasCost(hasCost);
            if (hasCost) {
                row.setGrossProfit(scale2(a.gross()));
                row.setMarginPct(a.marginPct());
            }
            // 周转天数(存销比):当前总存 ÷ 28 天日均
            DemandStats ds = demand.global.get(e.getKey());
            BigDecimal daily = ds == null ? null : ds.getAvgDaily();
            if (daily != null && daily.signum() > 0) {
                BigDecimal totalStock = nvl(warehouse.get(e.getKey())).add(nvl(machineTotal.get(e.getKey())));
                if (totalStock.signum() >= 0) {
                    row.setStockDays(totalStock.divide(daily, 1, RoundingMode.HALF_UP));
                }
            }
            BigDecimal[] lossCell = loss.get(e.getKey());
            if (lossCell != null) {
                row.setLossQty(lossCell[0]);
                row.setLossAmt(scale2(lossCell[1]));
            }
            resp.getRows().add(row);
        }
        resp.getRows().sort(bySalesDesc());
    }

    private void slotDim(Ctx ctx, MatrixResp resp) {
        Map<Long, Machine> machines = loadMachines();
        Map<Long, Product> products = loadProducts();
        // 货道骨架:planogram 里全部货道(没销量也出行,毛利/销量 0;绑定商品可见)
        Map<String, BiSlotRow> slotMap = new LinkedHashMap<>();
        Map<Long, Map<Long, Integer>> skuSlotCount = new HashMap<>(); // machine → product → 绑定货道数
        for (BiSlotRow sl : biQueryMapper.slotRows()) {
            slotMap.put(sl.getMachineId() + "#" + sl.getSlotNo(), sl);
            if (sl.getProductId() != null) {
                skuSlotCount.computeIfAbsent(sl.getMachineId(), k -> new HashMap<>())
                        .merge(sl.getProductId(), 1, Integer::sum);
            }
        }
        Map<String, Agg> bySlot = new LinkedHashMap<>();
        for (BiSaleRow s : ctx.sales) {
            if (!ctx.inMonth(s) || s.getMachineId() == null || StrUtil.isBlank(s.getSlotNo())) {
                continue;
            }
            SaleView v = view(ctx, s);
            if (v == null) {
                continue;
            }
            bySlot.computeIfAbsent(s.getMachineId() + "#" + s.getSlotNo(), k -> new Agg()).add(s, v);
        }
        // 吞货率分子:月内 吞货掉货 损耗(记到货道级的才算,记不到 → null)
        Map<String, BigDecimal> swallow = new HashMap<>();
        for (BiLossRow l : biQueryMapper.lossRows()) {
            if (ctx.month.equals(l.getMonth()) && l.getMachineId() != null
                    && StrUtil.isNotBlank(l.getSlotNo()) && "吞货掉货".equals(l.getReason())) {
                swallow.merge(l.getMachineId() + "#" + l.getSlotNo(), nvl(l.getQty()), BigDecimal::add);
            }
        }
        Set<String> keys = new LinkedHashSet<>(slotMap.keySet());
        keys.addAll(bySlot.keySet());
        for (String key : keys) {
            String[] parts = key.split("#", 2);
            Long machineId = Long.valueOf(parts[0]);
            String slotNo = parts[1];
            Machine m = machines.get(machineId);
            BiSlotRow sl = slotMap.get(key);
            Agg a = bySlot.get(key);
            MatrixRow row = new MatrixRow();
            row.setKey(machineId);
            row.setName((m == null ? "机器#" + machineId : m.getMachineName()) + " · " + slotNo + " 道");
            if (sl != null && sl.getProductId() != null) {
                Product p = products.get(sl.getProductId());
                row.setCode(p == null ? "SKU#" + sl.getProductId() : p.getProductName());
            }
            if (a != null) {
                row.setSalesQty(a.qty);
                row.setSalesAmt(scale2(a.amt));
                row.setGrossProfit(scale2(a.gross()));
                row.setMarginPct(a.marginPct());
                if (ctx.daysElapsed > 0) {
                    // 货道日均毛利 = 园区版"坪效"(货道值不值)
                    row.setDailyAvgAmt(a.gross().divide(BigDecimal.valueOf(ctx.daysElapsed), 2, RoundingMode.HALF_UP));
                }
                BigDecimal sw = swallow.get(key);
                if (sw != null && a.qty.signum() > 0) {
                    row.setSwallowRate(sw.multiply(BigDecimal.valueOf(100))
                            .divide(a.qty, 1, RoundingMode.HALF_UP));
                }
            }
            // 货道现量:同 SKU 只绑本货道才能给数(绑多道拆不开 → null + shared 标)
            if (sl != null && sl.getProductId() != null) {
                Integer bound = skuSlotCount.getOrDefault(machineId, Collections.emptyMap())
                        .get(sl.getProductId());
                if (bound != null && bound == 1) {
                    row.setEstQty(stockService.getMachineStock(machineId, sl.getProductId()));
                    row.setEstShared(false);
                } else {
                    row.setEstShared(true);
                }
            }
            resp.getRows().add(row);
        }
        resp.getRows().sort(bySalesDesc());
    }

    private void timeslotDim(Ctx ctx, MatrixResp resp) {
        // 12 × 2 小时桶(§10.1:时段维只有销售额/销量,毛利格 = null「—」)
        Agg[] buckets = new Agg[12];
        Agg[] weekdays = new Agg[7];
        for (int i = 0; i < 12; i++) {
            buckets[i] = new Agg();
        }
        for (int i = 0; i < 7; i++) {
            weekdays[i] = new Agg();
        }
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (BiSaleRow s : ctx.sales) {
            if (!ctx.inMonth(s)) {
                continue;
            }
            SaleView v = view(ctx, s);
            if (v == null) {
                continue;
            }
            buckets[s.getBizTime().getHour() / 2].add(s, v);
            weekdays[s.getBizTime().getDayOfWeek().getValue() - 1].add(s, v);
            totalAmt = totalAmt.add(v.amt);
        }
        for (int i = 0; i < 12; i++) {
            MatrixRow row = new MatrixRow();
            row.setKey((long) i);
            row.setName(i * 2 + "-" + (i * 2 + 2) + " 点");
            row.setSalesQty(buckets[i].qty);
            row.setSalesAmt(scale2(buckets[i].amt));
            row.setSharePct(pct(buckets[i].amt, totalAmt));
            resp.getRows().add(row);
        }
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        resp.setWeekdayRows(new ArrayList<>());
        for (int i = 0; i < 7; i++) {
            MatrixRow row = new MatrixRow();
            row.setKey((long) i);
            row.setName(names[i]);
            row.setSalesQty(weekdays[i].qty);
            row.setSalesAmt(scale2(weekdays[i].amt));
            row.setSharePct(pct(weekdays[i].amt, totalAmt));
            resp.getWeekdayRows().add(row);
        }
    }

    private void supplierDim(Ctx ctx, MatrixResp resp) {
        Map<Long, Supplier> suppliers = loadSuppliers();
        // 采购额:月×供应商;主供归属:SKU → 累计采购额最大供应商
        Map<Long, BigDecimal> monthPurchase = new LinkedHashMap<>();
        Map<Long, Map<Long, BigDecimal>> skuSupplierTotal = new HashMap<>(); // product → supplier → 累计额
        for (BiQueryMapper.BiSupplierPurchaseRow r : biQueryMapper.supplierPurchaseRows()) {
            if (ctx.month.equals(r.getMonth())) {
                monthPurchase.merge(r.getSupplierId(), nvl(r.getAmount()), BigDecimal::add);
            }
            skuSupplierTotal.computeIfAbsent(r.getProductId(), k -> new HashMap<>())
                    .merge(r.getSupplierId(), nvl(r.getAmount()), BigDecimal::add);
        }
        Map<Long, Long> mainSupplier = new HashMap<>(); // product → 主供
        for (Map.Entry<Long, Map<Long, BigDecimal>> e : skuSupplierTotal.entrySet()) {
            Long best = null;
            BigDecimal bestAmt = null;
            for (Map.Entry<Long, BigDecimal> se : e.getValue().entrySet()) {
                if (bestAmt == null || se.getValue().compareTo(bestAmt) > 0) {
                    best = se.getKey();
                    bestAmt = se.getValue();
                }
            }
            mainSupplier.put(e.getKey(), best);
        }
        // 本月各 SKU 毛利 → 归属主供
        Map<Long, BigDecimal> supplierGross = new HashMap<>();
        Map<Long, Set<Long>> supplierSkus = new HashMap<>();
        for (BiSaleRow s : ctx.sales) {
            if (!ctx.inMonth(s) || s.getProductId() == null) {
                continue;
            }
            SaleView v = view(ctx, s);
            if (v == null || v.cost == null) {
                continue;
            }
            Long sup = mainSupplier.get(s.getProductId());
            if (sup == null) {
                continue;
            }
            supplierGross.merge(sup, v.amt.subtract(v.cost), BigDecimal::add);
            supplierSkus.computeIfAbsent(sup, k -> new LinkedHashSet<>()).add(s.getProductId());
        }
        Map<Long, BigDecimal[]> poDiff = new HashMap<>();
        for (BiQueryMapper.BiPoDiffRow r : biQueryMapper.poDiffRows()) {
            poDiff.put(r.getSupplierId(), new BigDecimal[]{nvl(r.getOrdered()), nvl(r.getReceived())});
        }
        BigDecimal totalPurchase = BigDecimal.ZERO;
        for (BigDecimal v : monthPurchase.values()) {
            totalPurchase = totalPurchase.add(v);
        }
        Set<Long> ids = new LinkedHashSet<>(monthPurchase.keySet());
        ids.addAll(supplierGross.keySet());
        for (Long supplierId : ids) {
            Supplier sup = suppliers.get(supplierId);
            MatrixRow row = new MatrixRow();
            row.setKey(supplierId);
            row.setName(sup == null ? "供应商#" + supplierId : sup.getSupplierName());
            BigDecimal purchase = monthPurchase.get(supplierId);
            if (purchase != null) {
                row.setPurchaseAmt(scale2(purchase));
                row.setPurchaseSharePct(pct(purchase, totalPurchase));
            }
            BigDecimal gross = supplierGross.get(supplierId);
            if (gross != null) {
                row.setMarginContribution(scale2(gross));
                row.setAttributedSkuCount(supplierSkus.get(supplierId).size());
            }
            BigDecimal[] diff = poDiff.get(supplierId);
            if (diff != null && diff[0].signum() > 0) {
                row.setReceiveDiffRate(diff[0].subtract(diff[1]).multiply(BigDecimal.valueOf(100))
                        .divide(diff[0], 2, RoundingMode.HALF_UP));
            }
            resp.getRows().add(row);
        }
        resp.getRows().sort(Comparator.comparing(
                r -> r.getPurchaseAmt() == null ? BigDecimal.ZERO : r.getPurchaseAmt(),
                Comparator.reverseOrder()));
    }

    // ============================== 单品四象限 ==============================

    public BiDtos.QuadrantResp quadrant(String month) {
        Ctx ctx = new Ctx(month);
        BiDtos.QuadrantResp resp = new BiDtos.QuadrantResp();
        resp.setMonth(ctx.month);
        resp.setMonths(ctx.months);
        resp.setDataAsOf(ctx.dataAsOf);
        if (ctx.ym == null) {
            return resp;
        }
        Map<Long, Product> products = loadProducts();
        Map<Long, Agg> byProduct = new LinkedHashMap<>();
        for (BiSaleRow s : ctx.sales) {
            if (!ctx.inMonth(s) || s.getProductId() == null) {
                continue;
            }
            SaleView v = view(ctx, s);
            if (v == null) {
                continue;
            }
            byProduct.computeIfAbsent(s.getProductId(), k -> new Agg()).add(s, v);
        }
        List<BiDtos.QuadrantPoint> candidates = new ArrayList<>();
        for (Map.Entry<Long, Agg> e : byProduct.entrySet()) {
            Agg a = e.getValue();
            if (a.qty.signum() <= 0) {
                continue; // 净销量 ≤0(纯退款)不进象限
            }
            Product p = products.get(e.getKey());
            BiDtos.QuadrantPoint pt = new BiDtos.QuadrantPoint();
            pt.setProductId(e.getKey());
            pt.setName(p == null ? "SKU#" + e.getKey() : p.getProductName());
            pt.setSkuCode(p == null ? null : p.getSkuCode());
            pt.setCategory(p == null ? null : p.getCategory());
            pt.setSalesQty(a.qty);
            pt.setSalesAmt(scale2(a.amt));
            if (a.noCostSkus.isEmpty()) {
                pt.setGrossProfit(scale2(a.gross()));
                pt.setMarginPct(a.marginPct());
            }
            if (pt.getMarginPct() == null) {
                resp.getNoCostPoints().add(pt); // 毛利率算不出 → 不硬塞象限
            } else {
                candidates.add(pt);
            }
        }
        if (candidates.isEmpty()) {
            return resp;
        }
        List<BigDecimal> qtys = new ArrayList<>();
        List<BigDecimal> margins = new ArrayList<>();
        for (BiDtos.QuadrantPoint pt : candidates) {
            qtys.add(pt.getSalesQty());
            margins.add(pt.getMarginPct());
        }
        BigDecimal qtyMed = median(qtys);
        BigDecimal marginMed = median(margins);
        resp.setQtyMedian(qtyMed);
        resp.setMarginMedian(marginMed);
        for (BiDtos.QuadrantPoint pt : candidates) {
            boolean hiQty = pt.getSalesQty().compareTo(qtyMed) >= 0;
            boolean hiMargin = pt.getMarginPct().compareTo(marginMed) >= 0;
            pt.setQuadrant(hiQty ? (hiMargin ? "明星" : "引流") : (hiMargin ? "利基" : "淘汰"));
            resp.getPoints().add(pt);
        }
        resp.getPoints().sort(Comparator.comparing(BiDtos.QuadrantPoint::getSalesQty,
                Comparator.reverseOrder()));
        return resp;
    }

    // ============================== 客单价/连带/支付方式 ==============================

    public BiDtos.BasketResp basket(String month) {
        Ctx ctx = new Ctx(month);
        BiDtos.BasketResp resp = new BiDtos.BasketResp();
        resp.setMonth(ctx.month);
        resp.setMonths(ctx.months);
        resp.setDataAsOf(ctx.dataAsOf);
        if (ctx.ym == null) {
            return resp;
        }
        Map<Long, Product> products = loadProducts();
        // 客单价/连带:仅正常订单(退款/兑换/测试/线下不入)。
        // 「订单」= 同机器+同出货时间戳的一次消费(order_no 受 uk_sale_order 约束天然唯一,
        //  跨 SKU 不可能共享,故用 machine+biz_time 作会话键;本数据集单品消费为主,连带信号可能为空)。
        Map<String, BigDecimal> orderAmt = new LinkedHashMap<>();
        Map<String, Set<Long>> orderSkus = new LinkedHashMap<>();
        // 支付方式:正常+退款净额
        Map<String, BigDecimal[]> pay = new LinkedHashMap<>(); // method → [amt, count]
        BigDecimal payTotal = BigDecimal.ZERO;
        for (BiSaleRow s : ctx.sales) {
            if (!ctx.inMonth(s)) {
                continue;
            }
            String type = s.getOrderType() == null ? "正常" : s.getOrderType();
            if ("正常".equals(type)) {
                String session = s.getMachineId() + "@" + s.getBizTime();
                orderAmt.merge(session, nvl(s.getAmountReceived()), BigDecimal::add);
                if (s.getProductId() != null) {
                    orderSkus.computeIfAbsent(session, k -> new LinkedHashSet<>()).add(s.getProductId());
                }
            }
            if ("正常".equals(type) || "退款".equals(type)) {
                String method = StrUtil.isBlank(s.getPayMethod()) ? "未知" : s.getPayMethod();
                BigDecimal[] cell = pay.computeIfAbsent(method,
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                cell[0] = cell[0].add(nvl(s.getAmountReceived()));
                cell[1] = cell[1].add(BigDecimal.ONE);
                payTotal = payTotal.add(nvl(s.getAmountReceived()));
            }
        }
        int orders = orderAmt.size();
        resp.setOrderCount(orders);
        if (orders > 0) {
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal v : orderAmt.values()) {
                total = total.add(v);
            }
            resp.setAvgOrderValue(total.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP));
        }
        // 连带:同订单 ≥2 个不同 SKU → 两两组合计数
        Map<String, Integer> comboCount = new LinkedHashMap<>();
        int multi = 0;
        for (Set<Long> skus : orderSkus.values()) {
            if (skus.size() < 2) {
                continue;
            }
            multi++;
            List<Long> list = new ArrayList<>(skus);
            Collections.sort(list);
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    comboCount.merge(list.get(i) + "+" + list.get(j), 1, Integer::sum);
                }
            }
        }
        resp.setMultiItemOrderCount(multi);
        if (orders > 0) {
            resp.setMultiItemSharePct(BigDecimal.valueOf(multi * 100L)
                    .divide(BigDecimal.valueOf(orders), 1, RoundingMode.HALF_UP));
        }
        comboCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> {
                    String[] pair = e.getKey().split("\\+");
                    BiDtos.ComboRow row = new BiDtos.ComboRow();
                    row.setProductA(productName(products, Long.valueOf(pair[0])));
                    row.setProductB(productName(products, Long.valueOf(pair[1])));
                    row.setTimes(e.getValue());
                    resp.getCombos().add(row);
                });
        for (Map.Entry<String, BigDecimal[]> e : pay.entrySet()) {
            BiDtos.PayMethodRow row = new BiDtos.PayMethodRow();
            row.setPayMethod(e.getKey());
            row.setAmt(scale2(e.getValue()[0]));
            row.setOrderCount(e.getValue()[1].intValue());
            row.setSharePct(pct(e.getValue()[0], payTotal));
            resp.getPayMethods().add(row);
        }
        resp.getPayMethods().sort(Comparator.comparing(BiDtos.PayMethodRow::getAmt,
                Comparator.reverseOrder()));
        return resp;
    }

    // ============================== 缺货损失估算 ==============================

    /**
     * 缺货损失 = 估算缺货天数 × 有货日日均毛利(§10.1「少赚了多少,倒逼补货」)。
     * 只对「绑了货道的机器×SKU」估;无快照锚点/毛利算不出 → 该行不出(诚实边界)。
     */
    public BiDtos.StockoutLossResp stockoutLoss(String month) {
        Ctx ctx = new Ctx(month);
        return stockoutLossWithCtx(ctx);
    }

    private BiDtos.StockoutLossResp stockoutLossWithCtx(Ctx ctx) {
        BiDtos.StockoutLossResp resp = new BiDtos.StockoutLossResp();
        resp.setMonth(ctx.month);
        resp.setMonths(ctx.months);
        resp.setDataAsOf(ctx.dataAsOf);
        resp.setNote("估算口径:机器×SKU 库存轨迹=快照锚点+转移±/出货−(与库存推算同口径)逐日回放;"
                + "日末≤0 记缺货日;损失=缺货天数×该品该机有货日日均毛利。无锚点/无毛利数据的组合不估(不造假)。");
        if (ctx.ym == null || ctx.daysElapsed == 0) {
            return resp;
        }
        LocalDate monthStart = ctx.ym.atDay(1);
        LocalDate monthEnd = ctx.ym.atDay(ctx.daysElapsed);

        // 估算范围:绑了货道的机器×SKU
        Set<String> scope = new LinkedHashSet<>();
        for (BiSlotRow sl : biQueryMapper.slotRows()) {
            if (sl.getProductId() != null) {
                scope.add(sl.getMachineId() + "#" + sl.getProductId());
            }
        }
        if (scope.isEmpty()) {
            return resp;
        }
        // 分组事件流
        Map<String, List<BiSnapshotRow>> snaps = new HashMap<>();
        for (BiSnapshotRow r : biQueryMapper.snapshotRows()) {
            snaps.computeIfAbsent(r.getMachineId() + "#" + r.getProductId(), k -> new ArrayList<>()).add(r);
        }
        Map<String, List<BiMachineLedgerRow>> ledgers = new HashMap<>();
        for (BiMachineLedgerRow r : biQueryMapper.machineLedgerRows()) {
            ledgers.computeIfAbsent(r.getMachineId() + "#" + r.getProductId(), k -> new ArrayList<>()).add(r);
        }
        Map<String, List<BiSaleRow>> salesByKey = new HashMap<>();
        Map<String, BigDecimal> grossByKey = new HashMap<>(); // 本月毛利(机器×SKU,已计成本口径)
        for (BiSaleRow s : ctx.sales) {
            if (s.getMachineId() == null || s.getProductId() == null) {
                continue;
            }
            String key = s.getMachineId() + "#" + s.getProductId();
            salesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            if (ctx.inMonth(s)) {
                SaleView v = view(ctx, s);
                if (v != null && v.cost != null) {
                    grossByKey.merge(key, v.amt.subtract(v.cost), BigDecimal::add);
                }
            }
        }
        Map<Long, Machine> machines = loadMachines();
        Map<Long, Product> products = loadProducts();
        BigDecimal total = BigDecimal.ZERO;
        for (String key : scope) {
            StockoutEstimator.Trace t = StockoutEstimator.trace(monthStart, monthEnd,
                    snaps.get(key),
                    ledgers.getOrDefault(key, Collections.emptyList()),
                    salesByKey.getOrDefault(key, Collections.emptyList()));
            if (t == null || t.stockoutDays == 0) {
                continue; // 无锚点估不了 / 没缺过货
            }
            BigDecimal gross = grossByKey.get(key);
            if (gross == null || gross.signum() <= 0 || t.inStockDays == 0) {
                continue; // 毛利算不出(无成本/整月没卖出过)→ 损失无法量化,不造假
            }
            BigDecimal dailyGross = gross.divide(BigDecimal.valueOf(t.inStockDays), 2, RoundingMode.HALF_UP);
            BigDecimal loss = dailyGross.multiply(BigDecimal.valueOf(t.stockoutDays));
            String[] parts = key.split("#", 2);
            Long machineId = Long.valueOf(parts[0]);
            Long productId = Long.valueOf(parts[1]);
            BiDtos.StockoutLossRow row = new BiDtos.StockoutLossRow();
            row.setMachineId(machineId);
            Machine m = machines.get(machineId);
            row.setMachineName(m == null ? "机器#" + machineId : m.getMachineName());
            row.setProductId(productId);
            row.setProductName(productName(products, productId));
            row.setCoverageDays(t.coverageDays);
            row.setStockoutDays(t.stockoutDays);
            row.setDailyGross(dailyGross);
            row.setEstLoss(scale2(loss));
            resp.getRows().add(row);
            total = total.add(loss);
        }
        resp.getRows().sort(Comparator.comparing(BiDtos.StockoutLossRow::getEstLoss,
                Comparator.reverseOrder()));
        resp.setTotalEstLoss(scale2(total));
        return resp;
    }

    // ============================== 调价前后 14 天对比 ==============================

    public BiDtos.PriceCompareResp priceCompare() {
        Ctx ctx = new Ctx(null);
        BiDtos.PriceCompareResp resp = new BiDtos.PriceCompareResp();
        resp.setDataAsOf(ctx.dataAsOf);
        Map<Long, Product> products = loadProducts();
        // 按 SKU 建每日聚合索引(全量,窗口任意切)
        Map<Long, TreeMap<LocalDate, BigDecimal[]>> daily = new HashMap<>(); // product → date → [qty, amt, gross, costed]
        for (BiSaleRow s : ctx.sales) {
            if (s.getProductId() == null) {
                continue;
            }
            SaleView v = view(ctx, s);
            if (v == null) {
                continue;
            }
            BigDecimal[] cell = daily.computeIfAbsent(s.getProductId(), k -> new TreeMap<>())
                    .computeIfAbsent(s.getBizTime().toLocalDate(),
                            k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            cell[0] = cell[0].add(v.qtySigned);
            cell[1] = cell[1].add(v.amt);
            if (v.cost != null) {
                cell[2] = cell[2].add(v.amt.subtract(v.cost));
                cell[3] = BigDecimal.ONE;
            }
        }
        LocalDate asOfDay = ctx.dataAsOf == null ? null : ctx.dataAsOf.toLocalDate();
        for (BiQueryMapper.BiPriceLogRow log : biQueryMapper.priceLogRows()) {
            BiDtos.PriceCompareRow row = new BiDtos.PriceCompareRow();
            row.setProductId(log.getProductId());
            Product p = products.get(log.getProductId());
            row.setProductName(productName(products, log.getProductId()));
            row.setSkuCode(p == null ? null : p.getSkuCode());
            row.setOldPrice(log.getOldPrice());
            row.setNewPrice(log.getNewPrice());
            row.setEffectDate(log.getEffectDate().toString());
            row.setChangeSource(log.getChangeSource());
            LocalDate effect = log.getEffectDate();
            LocalDate beforeStart = effect.minusDays(14);
            LocalDate beforeEnd = effect.minusDays(1);
            LocalDate afterEnd = effect.plusDays(13);
            if (asOfDay != null && afterEnd.isAfter(asOfDay)) {
                afterEnd = asOfDay;
                row.setPartial(true);
            } else {
                row.setPartial(false);
            }
            int beforeDays = 14;
            int afterDays = afterEnd.isBefore(effect) ? 0
                    : (int) (afterEnd.toEpochDay() - effect.toEpochDay() + 1);
            row.setBeforeDays(beforeDays);
            row.setAfterDays(afterDays);
            TreeMap<LocalDate, BigDecimal[]> series = daily.get(log.getProductId());
            if (series != null) {
                BigDecimal[] before = windowSum(series, beforeStart, beforeEnd);
                row.setBeforeAvgQty(avg(before[0], beforeDays));
                row.setBeforeAvgAmt(avg(before[1], beforeDays));
                row.setBeforeGross(before[3].signum() > 0 ? scale2(before[2]) : null);
                if (afterDays > 0) {
                    BigDecimal[] after = windowSum(series, effect, afterEnd);
                    row.setAfterAvgQty(avg(after[0], afterDays));
                    row.setAfterAvgAmt(avg(after[1], afterDays));
                    row.setAfterGross(after[3].signum() > 0 ? scale2(after[2]) : null);
                    if (row.getBeforeAvgQty() != null && row.getBeforeAvgQty().signum() > 0
                            && row.getAfterAvgQty() != null) {
                        row.setQtyChangePct(row.getAfterAvgQty().subtract(row.getBeforeAvgQty())
                                .multiply(BigDecimal.valueOf(100))
                                .divide(row.getBeforeAvgQty(), 1, RoundingMode.HALF_UP));
                    }
                }
            }
            resp.getRows().add(row);
        }
        return resp;
    }

    /** [qtySum, amtSum, grossSum, anyCosted] */
    private static BigDecimal[] windowSum(TreeMap<LocalDate, BigDecimal[]> series,
                                          LocalDate from, LocalDate to) {
        BigDecimal[] sum = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map.Entry<LocalDate, BigDecimal[]> e : series.subMap(from, true, to, true).entrySet()) {
            sum[0] = sum[0].add(e.getValue()[0]);
            sum[1] = sum[1].add(e.getValue()[1]);
            sum[2] = sum[2].add(e.getValue()[2]);
            if (e.getValue()[3].signum() > 0) {
                sum[3] = BigDecimal.ONE;
            }
        }
        return sum;
    }

    // ============================== 工具 ==============================

    private static Comparator<MatrixRow> bySalesDesc() {
        return Comparator.comparing(r -> r.getSalesAmt() == null ? BigDecimal.ZERO : r.getSalesAmt(),
                Comparator.reverseOrder());
    }

    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 0) {
            return null;
        }
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 3, RoundingMode.HALF_UP);
    }

    private String category(Map<Long, Product> products, Long productId) {
        if (productId == null) {
            return "(未绑定商品)";
        }
        Product p = products.get(productId);
        return p == null ? UNCATEGORIZED : category(p);
    }

    private String category(Product p) {
        return StrUtil.isBlank(p.getCategory()) ? UNCATEGORIZED : p.getCategory();
    }

    private String productName(Map<Long, Product> products, Long productId) {
        Product p = products.get(productId);
        return p == null ? "SKU#" + productId : p.getProductName();
    }

    private Map<Long, Product> loadProducts() {
        Map<Long, Product> map = new LinkedHashMap<>();
        for (Product p : productMapper.selectList(null)) {
            map.put(p.getId(), p);
        }
        return map;
    }

    private Map<Long, Machine> loadMachines() {
        Map<Long, Machine> map = new LinkedHashMap<>();
        for (Machine m : machineMapper.selectList(null)) {
            map.put(m.getId(), m);
        }
        return map;
    }

    private Map<Long, Supplier> loadSuppliers() {
        Map<Long, Supplier> map = new LinkedHashMap<>();
        for (Supplier s : supplierMapper.selectList(null)) {
            map.put(s.getId(), s);
        }
        return map;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0 || part == null) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(BigDecimal sum, int days) {
        if (days <= 0) {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
