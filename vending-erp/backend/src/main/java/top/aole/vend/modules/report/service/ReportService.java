package top.aole.vend.modules.report.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.dto.ReportDtos.GrossMarginResp;
import top.aole.vend.modules.report.dto.ReportDtos.GrossMarginRow;
import top.aole.vend.modules.report.dto.ReportDtos.InventorySummaryResp;
import top.aole.vend.modules.report.dto.ReportDtos.InventorySummaryRow;
import top.aole.vend.modules.report.dto.ReportDtos.RecalcResp;
import top.aole.vend.modules.report.dto.ReportDtos.StockLedgerRow;
import top.aole.vend.modules.report.dto.ReportDtos.StockMachineCol;
import top.aole.vend.modules.report.dto.ReportDtos.StockResp;
import top.aole.vend.modules.report.dto.ReportDtos.StockRow;
import top.aole.vend.modules.report.mapper.ReportQueryMapper;
import top.aole.vend.modules.report.service.CostEngine.InvAgg;
import top.aole.vend.modules.report.service.CostEngine.MonthAgg;
import top.aole.vend.modules.report.service.CostEngine.Pool;
import top.aole.vend.modules.report.service.CostEngine.Replay;
import top.aole.vend.modules.stock.service.StockService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表服务(M1-6):毛利报表(SKU/机器 两维)+ 月度进销存汇总 + 库存查询 + 成本快照回写。
 * 口径全部来自 §13(毛利 = 实收 − 移动加权成本;结算=仅正常退款负;无采购史毛利显「—」)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    public static final String DIM_SKU = "sku";
    public static final String DIM_MACHINE = "machine";

    private final CostEngine costEngine;
    private final ReportQueryMapper reportQueryMapper;
    private final ProductMapper productMapper;
    private final MachineMapper machineMapper;
    private final StockService stockService;
    private final OpLogService opLogService;

    // ============================== 毛利报表 ==============================

    public GrossMarginResp grossMargin(String month, String dim) {
        Replay replay = costEngine.replay();
        GrossMarginResp resp = new GrossMarginResp();
        resp.setMonths(replay.getMonths());
        resp.setDataAsOf(reportQueryMapper.dataAsOf());
        if (replay.getMonths().isEmpty()) {
            resp.setMonth(month);
            resp.setDim(dim);
            return resp;
        }
        String m = StrUtil.isBlank(month) ? replay.getMonths().get(replay.getMonths().size() - 1) : month;
        boolean byMachine = DIM_MACHINE.equals(dim);
        resp.setMonth(m);
        resp.setDim(byMachine ? DIM_MACHINE : DIM_SKU);

        Map<String, MonthAgg> source = byMachine ? replay.getMachineMonth() : replay.getSkuMonth();
        Map<Long, Product> products = loadProducts();
        Map<Long, Machine> machines = loadMachines();

        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal costedSales = BigDecimal.ZERO;
        int noCostCount = 0;

        String suffix = "" + m;
        for (Map.Entry<String, MonthAgg> e : source.entrySet()) {
            if (!e.getKey().endsWith(suffix)) {
                continue;
            }
            long key = Long.parseLong(e.getKey().substring(0, e.getKey().indexOf('')));
            MonthAgg agg = e.getValue();
            GrossMarginRow row = new GrossMarginRow();
            row.setKey(key == Replay.UNBOUND_KEY ? null : key);
            if (byMachine) {
                Machine machine = machines.get(key);
                row.setName(machine == null ? "机器#" + key : machine.getMachineName());
                row.setCode(machine == null ? "" : machine.getMachineCode());
                row.setNoCostSkuCount(agg.getNoCostSkuCount());
                row.setHasCost(true); // 机器行按已计部分展示,另标无成本 SKU 数
            } else if (key == Replay.UNBOUND_KEY) {
                row.setName("(未绑定商品)");
                row.setCode("—");
                row.setHasCost(false);
            } else {
                Product p = products.get(key);
                row.setName(p == null ? "SKU#" + key : p.getProductName());
                row.setCode(p == null ? "" : p.getSkuCode());
                row.setHasCost(!agg.isNoCost());
            }
            row.setSalesQty(agg.getSalesQty());
            row.setSalesAmt(scale2(agg.getSalesAmt()));
            totalSales = totalSales.add(agg.getSalesAmt());
            if (row.isHasCost()) {
                row.setCostAmt(scale2(agg.getCostAmt()));
                BigDecimal gross = agg.getSalesAmt().subtract(agg.getCostAmt());
                row.setGrossProfit(scale2(gross));
                if (agg.getSalesAmt().signum() != 0) {
                    row.setMarginPct(gross.multiply(BigDecimal.valueOf(100))
                            .divide(agg.getSalesAmt(), 2, RoundingMode.HALF_UP));
                }
                totalCost = totalCost.add(agg.getCostAmt());
                totalGross = totalGross.add(gross);
                costedSales = costedSales.add(agg.getSalesAmt());
            } else {
                noCostCount++;
            }
            resp.getRows().add(row);
        }
        resp.getRows().sort(Comparator.comparing(
                r -> r.getSalesAmt() == null ? BigDecimal.ZERO : r.getSalesAmt(),
                Comparator.reverseOrder()));
        resp.setTotalSalesAmt(scale2(totalSales));
        resp.setTotalCostAmt(scale2(totalCost));
        resp.setTotalGrossProfit(scale2(totalGross));
        resp.setCostedSalesAmt(scale2(costedSales));
        resp.setNoCostCount(noCostCount);
        if (costedSales.signum() != 0) {
            resp.setTotalMarginPct(totalGross.multiply(BigDecimal.valueOf(100))
                    .divide(costedSales, 2, RoundingMode.HALF_UP));
        }
        return resp;
    }

    // ============================== 进销存汇总 ==============================

    public InventorySummaryResp inventorySummary(String month) {
        Replay replay = costEngine.replay();
        InventorySummaryResp resp = new InventorySummaryResp();
        resp.setMonths(replay.getMonths());
        resp.setDataAsOf(reportQueryMapper.dataAsOf());
        if (replay.getMonths().isEmpty()) {
            resp.setMonth(month);
            return resp;
        }
        String m = StrUtil.isBlank(month) ? replay.getMonths().get(replay.getMonths().size() - 1) : month;
        resp.setMonth(m);
        Map<Long, Product> products = loadProducts();

        InventorySummaryRow total = new InventorySummaryRow();
        total.setName("合计");
        String suffix = "" + m;
        for (Map.Entry<String, InvAgg> e : replay.getInvMonth().entrySet()) {
            if (!e.getKey().endsWith(suffix)) {
                continue;
            }
            long productId = Long.parseLong(e.getKey().substring(0, e.getKey().indexOf('')));
            InvAgg agg = e.getValue();
            Pool pool = replay.getPools().get(productId);
            boolean hasCost = pool != null && pool.hasCost();
            Product p = products.get(productId);
            InventorySummaryRow row = new InventorySummaryRow();
            row.setProductId(productId);
            row.setCode(p == null ? "" : p.getSkuCode());
            row.setName(p == null ? "SKU#" + productId : p.getProductName());
            row.setOpeningQty(agg.getOpeningQty());
            row.setInQty(agg.getInQty());
            row.setOutQty(agg.getOutQty());
            row.setClosingQty(agg.getClosingQty());
            row.setHasCost(hasCost);
            if (hasCost) {
                row.setOpeningAmt(scale2(agg.getOpeningVal()));
                row.setInAmt(scale2(agg.getInAmt()));
                row.setOutAmt(scale2(agg.getOutAmt()));
                row.setClosingAmt(scale2(agg.getClosingVal()));
                total.setOpeningAmt(nvl(total.getOpeningAmt()).add(agg.getOpeningVal()));
                total.setInAmt(nvl(total.getInAmt()).add(agg.getInAmt()));
                total.setOutAmt(nvl(total.getOutAmt()).add(agg.getOutAmt()));
                total.setClosingAmt(nvl(total.getClosingAmt()).add(agg.getClosingVal()));
            }
            total.setOpeningQty(nvl(total.getOpeningQty()).add(agg.getOpeningQty()));
            total.setInQty(nvl(total.getInQty()).add(agg.getInQty()));
            total.setOutQty(nvl(total.getOutQty()).add(agg.getOutQty()));
            total.setClosingQty(nvl(total.getClosingQty()).add(agg.getClosingQty()));
            resp.getRows().add(row);
        }
        resp.getRows().sort(Comparator.comparing(InventorySummaryRow::getCode,
                Comparator.nullsLast(Comparator.naturalOrder())));
        total.setOpeningAmt(scale2(total.getOpeningAmt()));
        total.setInAmt(scale2(total.getInAmt()));
        total.setOutAmt(scale2(total.getOutAmt()));
        total.setClosingAmt(scale2(total.getClosingAmt()));
        resp.setTotal(total);
        return resp;
    }

    // ============================== 库存查询 ==============================

    public StockResp stock() {
        Replay replay = costEngine.replay();
        StockResp resp = new StockResp();
        resp.setDataAsOf(reportQueryMapper.dataAsOf());

        List<Machine> machines = machineMapper.selectList(
                new LambdaQueryWrapper<Machine>().orderByAsc(Machine::getId));
        for (Machine machine : machines) {
            StockMachineCol col = new StockMachineCol();
            col.setMachineId(machine.getId());
            col.setMachineName(machine.getMachineName());
            resp.getMachines().add(col);
        }
        // 机器库存:每台机器全 SKU 推算(锚点+增量,M1-5)
        Map<Long, Map<Long, BigDecimal>> byMachine = new LinkedHashMap<>();
        for (Machine machine : machines) {
            byMachine.put(machine.getId(), stockService.getMachineStockAll(machine.getId()));
        }

        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>().orderByAsc(Product::getSkuCode));
        List<Long> productIds = new ArrayList<>();
        for (Product p : products) {
            productIds.add(p.getId());
        }
        Map<Long, BigDecimal> warehouse = productIds.isEmpty()
                ? new LinkedHashMap<>() : stockService.getWarehouseStockBatch(productIds);

        BigDecimal warehouseAmount = BigDecimal.ZERO;
        BigDecimal machineAmount = BigDecimal.ZERO;
        int negativeCount = 0;
        for (Product p : products) {
            StockRow row = new StockRow();
            row.setProductId(p.getId());
            row.setCode(p.getSkuCode());
            row.setName(p.getProductName());
            row.setCategory(p.getCategory());
            row.setProductStatus(p.getProductStatus());
            BigDecimal wh = nvl(warehouse.get(p.getId()));
            row.setWarehouseQty(wh);
            BigDecimal machineSum = BigDecimal.ZERO;
            boolean negative = wh.signum() < 0;
            for (Machine machine : machines) {
                BigDecimal q = byMachine.get(machine.getId()).get(p.getId());
                if (q == null) {
                    continue;
                }
                row.getMachineQty().put(machine.getId(), q);
                machineSum = machineSum.add(q);
                if (q.signum() < 0) {
                    negative = true;
                }
            }
            row.setTotalQty(wh.add(machineSum));
            Pool pool = replay.getPools().get(p.getId());
            BigDecimal unitCost = pool == null ? null : pool.currentAvg();
            row.setUnitCost(unitCost == null ? null : unitCost.setScale(4, RoundingMode.HALF_UP));
            if (unitCost != null) {
                row.setAmount(scale2(row.getTotalQty().multiply(unitCost)));
                warehouseAmount = warehouseAmount.add(wh.multiply(unitCost));
                machineAmount = machineAmount.add(machineSum.multiply(unitCost));
            }
            row.setNegative(negative);
            if (negative) {
                negativeCount++;
            }
            resp.getRows().add(row);
        }
        resp.setWarehouseAmount(scale2(warehouseAmount));
        resp.setMachineAmount(scale2(machineAmount));
        resp.setTotalAmount(scale2(warehouseAmount.add(machineAmount)));
        resp.setNegativeCount(negativeCount);
        return resp;
    }

    public List<StockLedgerRow> productLedger(Long productId, int limit) {
        if (productId == null) {
            throw new BizException("productId 不能为空");
        }
        return reportQueryMapper.productLedger(productId, Math.min(Math.max(limit, 1), 500));
    }

    // ============================== 成本快照回写 ==============================

    /**
     * 成本重算回写(附录C:出库行按当前单位成本结转并把成本快照回写):
     * sale_record.cost_amount + stock_ledger 出库/转移行 unit_cost/amount。
     * 报表本身动态算不依赖回写;回写供 M3 结算/M4 BI 直接读快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecalcResp recalc(String operator) {
        Replay replay = costEngine.replay();
        int saleUpdated = 0;
        for (Map.Entry<Long, BigDecimal> e : replay.getSaleCost().entrySet()) {
            saleUpdated += reportQueryMapper.updateSaleCost(e.getKey(), e.getValue());
        }
        for (Long saleId : replay.getNoCostSaleIds()) {
            saleUpdated += reportQueryMapper.updateSaleCost(saleId, null);
        }
        int ledgerUpdated = 0;
        for (Map.Entry<Long, BigDecimal> e : replay.getLedgerUnitCost().entrySet()) {
            BigDecimal unitCost = e.getValue().setScale(4, RoundingMode.HALF_UP);
            ledgerUpdated += reportQueryMapper.updateLedgerCost(e.getKey(), unitCost, null);
        }
        RecalcResp resp = new RecalcResp();
        resp.setSaleUpdated(saleUpdated);
        resp.setLedgerUpdated(ledgerUpdated);
        resp.setProducts(replay.getPools().size());
        opLogService.record(operator, "成本重算回写", "stock_ledger", null, null,
                "sale=" + saleUpdated + " ledger=" + ledgerUpdated);
        log.info("成本重算回写完成:sale={} ledger={} products={}", saleUpdated, ledgerUpdated,
                replay.getPools().size());
        return resp;
    }

    // ============================== 单品详情(M1-9,只读聚合) ==============================

    /**
     * 单品体检报告(p14):档案 + 两级库存 + 30 天销量/毛利 + 周走势 + 机器分布 + 采购史。
     * "今天" = dataAsOf(数据截至水印),30 天窗口与够卖天数都以它为锚——历史数据也能出报告。
     */
    public ReportDtos.ProductOverviewResp productOverview(Long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null) {
            throw new BizException("商品不存在:" + productId);
        }
        Replay replay = costEngine.replay();
        java.time.LocalDateTime asOf = reportQueryMapper.dataAsOf();

        ReportDtos.ProductOverviewResp resp = new ReportDtos.ProductOverviewResp();
        resp.setProductId(p.getId());
        resp.setSkuCode(p.getSkuCode());
        resp.setProductName(p.getProductName());
        resp.setCategory(p.getCategory());
        resp.setProductStatus(p.getProductStatus());
        resp.setUnit(p.getUnit());
        resp.setBoxSpec(p.getBoxSpec());
        resp.setShelfLifeDays(p.getShelfLifeDays());
        resp.setRefPrice(p.getRefPrice());
        resp.setDataAsOf(asOf);

        // ---- 两级库存 ----
        BigDecimal wh = nvl(stockService.getWarehouseStock(productId));
        resp.setWarehouseQty(wh);
        List<Machine> machines = machineMapper.selectList(
                new LambdaQueryWrapper<Machine>().orderByAsc(Machine::getId));
        BigDecimal machineSum = BigDecimal.ZERO;
        Map<Long, BigDecimal> stockByMachine = new LinkedHashMap<>();
        for (Machine machine : machines) {
            BigDecimal q = stockService.getMachineStock(machine.getId(), productId);
            if (q != null && q.signum() != 0) {
                stockByMachine.put(machine.getId(), q);
                machineSum = machineSum.add(q);
            }
        }
        resp.setMachineQtyTotal(machineSum);
        resp.setTotalQty(wh.add(machineSum));
        Pool pool = replay.getPools().get(productId);
        BigDecimal unitCost = pool == null ? null : pool.currentAvg();
        resp.setHasCost(unitCost != null);
        if (unitCost != null) {
            resp.setUnitCost(unitCost.setScale(4, RoundingMode.HALF_UP));
            resp.setStockAmount(scale2(resp.getTotalQty().multiply(unitCost)));
        }

        // ---- 销售:30 天口径 + 周走势(8 周)+ 机器分布 ----
        java.time.LocalDateTime win30 = asOf == null ? null : asOf.minusDays(30);
        java.time.LocalDate weekStart0 = asOf == null ? null
                : asOf.toLocalDate().minusWeeks(7).with(java.time.DayOfWeek.MONDAY);
        Map<String, ReportDtos.TrendPoint> weeks = new LinkedHashMap<>();
        if (weekStart0 != null) {
            for (int i = 0; i < 8; i++) {
                String label = weekStart0.plusWeeks(i).toString();
                ReportDtos.TrendPoint tp = new ReportDtos.TrendPoint();
                tp.setLabel(label);
                weeks.put(label, tp);
            }
        }
        Map<Long, ReportDtos.MachineDistRow> dist = new LinkedHashMap<>();
        BigDecimal cost30 = BigDecimal.ZERO;
        boolean anyCost30 = false;
        boolean allCost30 = true;
        for (top.aole.vend.modules.report.dto.ReportDtos.SaleEvent ev : reportQueryMapper.saleEvents()) {
            if (!productId.equals(ev.getProductId()) || "测试".equals(ev.getOrderType())) {
                continue;
            }
            BigDecimal saleCost = replay.getSaleCost().get(ev.getId());
            // 30 天窗口
            if (win30 != null && !ev.getBizTime().isBefore(win30)) {
                resp.setSalesQty30(resp.getSalesQty30().add(nvl(ev.getQty())));
                resp.setSalesAmt30(resp.getSalesAmt30().add(nvl(ev.getAmountReceived())));
                if (saleCost != null) {
                    cost30 = cost30.add(saleCost);
                    anyCost30 = true;
                } else {
                    allCost30 = false;
                }
                if (ev.getMachineId() != null) {
                    ReportDtos.MachineDistRow row = dist.computeIfAbsent(ev.getMachineId(), k -> {
                        ReportDtos.MachineDistRow r = new ReportDtos.MachineDistRow();
                        r.setMachineId(k);
                        return r;
                    });
                    row.setSalesQty30(row.getSalesQty30().add(nvl(ev.getQty())));
                }
            }
            // 周走势
            if (weekStart0 != null && !ev.getBizTime().toLocalDate().isBefore(weekStart0)) {
                String label = ev.getBizTime().toLocalDate().with(java.time.DayOfWeek.MONDAY).toString();
                ReportDtos.TrendPoint tp = weeks.get(label);
                if (tp != null) {
                    tp.setSalesQty(tp.getSalesQty().add(nvl(ev.getQty())));
                    tp.setSalesAmt(tp.getSalesAmt().add(nvl(ev.getAmountReceived())));
                    if (saleCost != null) {
                        tp.setGrossProfit(tp.getGrossProfit().add(nvl(ev.getAmountReceived()).subtract(saleCost)));
                    }
                }
            }
        }
        if (anyCost30 && allCost30) {
            BigDecimal gross = resp.getSalesAmt30().subtract(cost30);
            resp.setGrossProfit30(scale2(gross));
            if (resp.getSalesAmt30().signum() != 0) {
                resp.setMarginPct30(gross.multiply(BigDecimal.valueOf(100))
                        .divide(resp.getSalesAmt30(), 2, RoundingMode.HALF_UP));
            }
        }
        resp.setSalesAmt30(scale2(resp.getSalesAmt30()));
        resp.setDailyAvg30(resp.getSalesQty30().divide(BigDecimal.valueOf(30), 1, RoundingMode.HALF_UP));
        if (resp.getDailyAvg30().signum() > 0 && resp.getTotalQty().signum() > 0) {
            resp.setDaysOfStock(resp.getTotalQty().divide(resp.getDailyAvg30(), 1, RoundingMode.HALF_UP));
        }
        resp.getWeeklyTrend().addAll(weeks.values());
        // 机器分布补名字 + 现存(含只有库存没销量的机器)
        for (Machine machine : machines) {
            BigDecimal stockQ = stockByMachine.get(machine.getId());
            ReportDtos.MachineDistRow row = dist.get(machine.getId());
            if (row == null && stockQ == null) {
                continue;
            }
            if (row == null) {
                row = new ReportDtos.MachineDistRow();
                row.setMachineId(machine.getId());
                dist.put(machine.getId(), row);
            }
            row.setMachineName(machine.getMachineName());
            row.setStockQty(stockQ);
        }
        resp.getMachineDist().addAll(dist.values());
        resp.getMachineDist().sort(Comparator.comparing(ReportDtos.MachineDistRow::getSalesQty30,
                Comparator.reverseOrder()));

        // ---- 采购史(近 10 笔 + 累计) ----
        List<ReportDtos.PurchaseHistRow> hist = reportQueryMapper.purchaseHist(productId, 10);
        resp.getPurchaseHist().addAll(hist);
        for (ReportDtos.PurchaseHistRow h : hist) {
            resp.setPurchaseTotalQty(resp.getPurchaseTotalQty().add(nvl(h.getQty())));
            resp.setPurchaseTotalAmt(resp.getPurchaseTotalAmt().add(nvl(h.getAmount())));
        }
        resp.setPurchaseTotalAmt(scale2(resp.getPurchaseTotalAmt()));
        return resp;
    }

    // ============================== 机器详情(M1-9,只读聚合) ==============================

    /**
     * 机器体检报告(p15):档案 + 当月销售/毛利 + 14 天走势 + 本机 TOP SKU + 货道 planogram + 补货史。
     */
    public ReportDtos.MachineOverviewResp machineOverview(Long machineId) {
        Machine machine = machineMapper.selectById(machineId);
        if (machine == null) {
            throw new BizException("机器不存在:" + machineId);
        }
        Replay replay = costEngine.replay();
        java.time.LocalDateTime asOf = reportQueryMapper.dataAsOf();

        ReportDtos.MachineOverviewResp resp = new ReportDtos.MachineOverviewResp();
        resp.setMachineId(machine.getId());
        resp.setMachineCode(machine.getMachineCode());
        resp.setMachineName(machine.getMachineName());
        resp.setDeviceId(machine.getDeviceId());
        resp.setLocation(machine.getLocation());
        resp.setModel(machine.getModel());
        resp.setSlotCount(machine.getSlotCount());
        resp.setMachineStatus(machine.getMachineStatus());
        resp.setOnlineDate(machine.getOnlineDate() == null ? null : machine.getOnlineDate().toString());
        resp.setDataAsOf(asOf);

        // 锚点:该机最近一笔销售(没有则退回 dataAsOf)——历史数据也能出"最近有售月"的体检
        List<top.aole.vend.modules.report.dto.ReportDtos.SaleEvent> allSales = reportQueryMapper.saleEvents();
        java.time.LocalDateTime lastSale = null;
        for (top.aole.vend.modules.report.dto.ReportDtos.SaleEvent ev : allSales) {
            if (machineId.equals(ev.getMachineId()) && !"测试".equals(ev.getOrderType())
                    && (lastSale == null || ev.getBizTime().isAfter(lastSale))) {
                lastSale = ev.getBizTime();
            }
        }
        java.time.LocalDateTime anchor = lastSale != null ? lastSale : asOf;
        String month = anchor == null ? null : anchor.toLocalDate().toString().substring(0, 7);
        resp.setMonth(month);

        // ---- 当月销售/毛利 + 全场占比 + 14 天走势 + 30 天 TOP SKU ----
        java.time.LocalDate day0 = anchor == null ? null : anchor.toLocalDate().minusDays(13);
        Map<String, ReportDtos.TrendPoint> days = new LinkedHashMap<>();
        if (day0 != null) {
            for (int i = 0; i < 14; i++) {
                String label = day0.plusDays(i).toString();
                ReportDtos.TrendPoint tp = new ReportDtos.TrendPoint();
                tp.setLabel(label);
                days.put(label, tp);
            }
        }
        java.time.LocalDateTime win30 = anchor == null ? null : anchor.minusDays(30);
        Map<Long, ReportDtos.SkuSalesRow> topMap = new LinkedHashMap<>();
        BigDecimal monthCost = BigDecimal.ZERO;
        boolean anyMonthCost = false;
        BigDecimal allSalesInMonth = BigDecimal.ZERO;
        java.util.Set<java.time.LocalDate> saleDays = new java.util.HashSet<>();
        for (top.aole.vend.modules.report.dto.ReportDtos.SaleEvent ev : allSales) {
            if ("测试".equals(ev.getOrderType())) {
                continue;
            }
            boolean inMonth = month != null && month.equals(ev.getBizPeriod());
            if (inMonth) {
                allSalesInMonth = allSalesInMonth.add(nvl(ev.getAmountReceived()));
            }
            if (!machineId.equals(ev.getMachineId())) {
                continue;
            }
            BigDecimal saleCost = replay.getSaleCost().get(ev.getId());
            if (inMonth) {
                resp.setMonthSalesAmt(resp.getMonthSalesAmt().add(nvl(ev.getAmountReceived())));
                resp.setMonthSalesQty(resp.getMonthSalesQty().add(nvl(ev.getQty())));
                if (saleCost != null) {
                    monthCost = monthCost.add(saleCost);
                    anyMonthCost = true;
                }
                saleDays.add(ev.getBizTime().toLocalDate());
            }
            if (day0 != null && !ev.getBizTime().toLocalDate().isBefore(day0)) {
                ReportDtos.TrendPoint tp = days.get(ev.getBizTime().toLocalDate().toString());
                if (tp != null) {
                    tp.setSalesQty(tp.getSalesQty().add(nvl(ev.getQty())));
                    tp.setSalesAmt(tp.getSalesAmt().add(nvl(ev.getAmountReceived())));
                    if (saleCost != null) {
                        tp.setGrossProfit(tp.getGrossProfit().add(nvl(ev.getAmountReceived()).subtract(saleCost)));
                    }
                }
            }
            if (win30 != null && !ev.getBizTime().isBefore(win30) && ev.getProductId() != null) {
                ReportDtos.SkuSalesRow row = topMap.computeIfAbsent(ev.getProductId(), k -> {
                    ReportDtos.SkuSalesRow r = new ReportDtos.SkuSalesRow();
                    r.setProductId(k);
                    return r;
                });
                row.setSalesQty30(row.getSalesQty30().add(nvl(ev.getQty())));
                row.setSalesAmt30(row.getSalesAmt30().add(nvl(ev.getAmountReceived())));
            }
        }
        resp.setMonthSalesAmt(scale2(resp.getMonthSalesAmt()));
        if (anyMonthCost) {
            BigDecimal gross = resp.getMonthSalesAmt().subtract(monthCost);
            resp.setMonthGrossProfit(scale2(gross));
            if (resp.getMonthSalesAmt().signum() != 0) {
                resp.setMonthMarginPct(gross.multiply(BigDecimal.valueOf(100))
                        .divide(resp.getMonthSalesAmt(), 2, RoundingMode.HALF_UP));
            }
        }
        if (allSalesInMonth.signum() != 0) {
            resp.setSalesSharePct(resp.getMonthSalesAmt().multiply(BigDecimal.valueOf(100))
                    .divide(allSalesInMonth, 1, RoundingMode.HALF_UP));
        }
        if (!saleDays.isEmpty()) {
            resp.setDailyAvgAmt(resp.getMonthSalesAmt()
                    .divide(BigDecimal.valueOf(saleDays.size()), 2, RoundingMode.HALF_UP));
        }
        resp.getDailyTrend().addAll(days.values());

        // TOP SKU 补名字,取前 8
        Map<Long, Product> products = loadProducts();
        List<ReportDtos.SkuSalesRow> tops = new ArrayList<>(topMap.values());
        tops.sort(Comparator.comparing(ReportDtos.SkuSalesRow::getSalesQty30, Comparator.reverseOrder()));
        for (ReportDtos.SkuSalesRow row : tops) {
            Product p = products.get(row.getProductId());
            row.setProductName(p == null ? "SKU#" + row.getProductId() : p.getProductName());
            row.setSkuCode(p == null ? "" : p.getSkuCode());
        }
        resp.getTopSkus().addAll(tops.subList(0, Math.min(8, tops.size())));

        // ---- 机内库存(推算)+ 货道 planogram + 补货史 ----
        Map<Long, BigDecimal> stockBySku = stockService.getMachineStockAll(machineId);
        BigDecimal stockSum = BigDecimal.ZERO;
        for (BigDecimal q : stockBySku.values()) {
            stockSum = stockSum.add(nvl(q));
        }
        resp.setMachineStockQty(stockSum);
        List<ReportDtos.SlotRow> slots = reportQueryMapper.machineSlots(machineId);
        // P1-2 整改:slot.current_qty 无写手恒 0(假红灯),格子现量改用该机 SKU 推算库存。
        // 同 SKU 绑多货道无法拆分 → 每格都给 SKU 级合并量 + estShared 标记,配色分母用同 SKU 容量合计。
        Map<Long, List<ReportDtos.SlotRow>> slotsBySku = new LinkedHashMap<>();
        for (ReportDtos.SlotRow s : slots) {
            if (s.getProductId() != null) {
                slotsBySku.computeIfAbsent(s.getProductId(), k -> new ArrayList<>()).add(s);
            }
        }
        for (Map.Entry<Long, List<ReportDtos.SlotRow>> e : slotsBySku.entrySet()) {
            List<ReportDtos.SlotRow> group = e.getValue();
            BigDecimal est = stockBySku.get(e.getKey());
            boolean shared = group.size() > 1;
            BigDecimal capSum = BigDecimal.ZERO;
            for (ReportDtos.SlotRow s : group) {
                capSum = capSum.add(nvl(s.getCapacity()));
            }
            for (ReportDtos.SlotRow s : group) {
                s.setEstQty(est);
                s.setEstShared(shared);
                s.setEstCapacity(shared ? capSum : s.getCapacity());
            }
        }
        resp.getSlots().addAll(slots);
        BigDecimal cap = BigDecimal.ZERO;
        for (ReportDtos.SlotRow s : slots) {
            cap = cap.add(nvl(s.getCapacity()));
        }
        resp.setCapacityTotal(cap);
        resp.getTransferHist().addAll(reportQueryMapper.machineTransferHist(machineId, 10));
        return resp;
    }

    // ============================== 内部 ==============================

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

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
