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
