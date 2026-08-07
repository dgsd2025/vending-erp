package top.aole.vend.modules.monthly.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.service.AssetSnapshotService;
import top.aole.vend.modules.finreport.service.ProfitReportService;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.CashFlowQueryService;
import top.aole.vend.modules.monthly.dto.MonthlyDtos;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.service.ReportService;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.service.PayableService;
import top.aole.vend.modules.settlement.service.SettlementService;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度报表包聚合服务(M4-4,§10.3 前六件套 + ⑥ 资产快照)。
 *
 * 唯一职责:把已建成的报表/钱账/损耗/资产服务的产出,按"月"这个口径聚成一个包,只读、不重算。
 * 口径月的解析统一委托利润表(ProfitReportService.monthly 的 bookPeriods),保证包内七件套同一口径月。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    private final ReportService reportService;
    private final ProfitReportService profitReportService;
    private final AssetSnapshotService assetSnapshotService;
    private final CashFlowQueryService cashFlowQueryService;
    private final AccountService accountService;
    private final PayableService payableService;
    private final SettlementService settlementService;
    private final StocktakeService stocktakeService;

    /** 趋势/损耗回溯窗口 */
    private static final int TREND_MONTHS = 12;
    private static final int LOSS_LOOKBACK = 13;

    /** 报表包总览:六件套 + 资产。month 空 → 取最近有数据的 book_period。 */
    public MonthlyDtos.PackageResp buildPackage(String month) {
        MonthlyDtos.PackageResp resp = new MonthlyDtos.PackageResp();

        // ① 利润表先算(顺带解析口径月 + months 列表,全包统一口径)
        FinReportDtos.ProfitResp profit = profitReportService.monthly(month);
        String m = profit.getPeriod();
        resp.setMonth(m);
        resp.setMonths(profit.getMonths());
        resp.setLocked(profit.isLocked());
        resp.setProfit(profit);

        // ② 进销存汇总表
        ReportDtos.InventorySummaryResp inv = reportService.inventorySummary(m);
        resp.setInventory(inv);
        resp.setDataAsOf(inv.getDataAsOf());

        // ③ 现金流水汇总
        resp.setCashflow(cashflowSummary(m));

        // ④ 往来表
        resp.setPayable(payableSummary());

        // ⑤ 损耗报表
        resp.setLoss(lossSummary(m));

        // ⑥ 资产快照 + 趋势
        resp.setAsset(assetSummary());

        return resp;
    }

    // ============================== ③ 现金流水汇总 ==============================

    /**
     * 按账户/类别聚合本月现金流水 + 各账户月末余额。
     * 月末余额 = 期初余额 + Σ(book_period ≤ 本月)净额(收正支负);当月活动只统计 book_period == 本月。
     */
    public MonthlyDtos.CashflowSummary cashflowSummary(String month) {
        MonthlyDtos.CashflowSummary cs = new MonthlyDtos.CashflowSummary();
        cs.setMonth(month);

        // 一次拉取:book_period ≤ 本月 的全部流水(toPeriod=month)——既算月末余额又筛当月活动
        Page<MoneyDtos.FlowRow> page = cashFlowQueryService.page(1, 100000, null, null, null, month);
        List<MoneyDtos.FlowRow> flows = page.getRecords();

        Map<String, MonthlyDtos.CashflowRow> byAccount = new LinkedHashMap<>();
        Map<String, MonthlyDtos.CashflowRow> byCategory = new LinkedHashMap<>();
        // 账户ID → 截至本月末的净额(含历史)
        Map<Long, BigDecimal> netToMonthEnd = new LinkedHashMap<>();

        BigDecimal totalIn = BigDecimal.ZERO;
        BigDecimal totalOut = BigDecimal.ZERO;
        for (MoneyDtos.FlowRow f : flows) {
            boolean in = CashFlow.DIR_IN.equals(f.getDirection());
            BigDecimal amt = nz(f.getAmount());
            BigDecimal signed = in ? amt : amt.negate();
            // 月末余额:全部 ≤ 本月的流水都累加
            netToMonthEnd.merge(f.getAccountId(), signed, BigDecimal::add);

            // 当月活动:仅 book_period == 本月
            if (!month.equals(f.getBookPeriod())) {
                continue;
            }
            String acctName = StrUtil.blankToDefault(f.getAccountName(), "账户#" + f.getAccountId());
            accumulate(byAccount, acctName, in, amt);
            String cat = StrUtil.blankToDefault(f.getPlLine(), StrUtil.blankToDefault(f.getCategory(), "未分类"));
            accumulate(byCategory, cat, in, amt);
            if (in) {
                totalIn = totalIn.add(amt);
            } else {
                totalOut = totalOut.add(amt);
            }
        }

        cs.getByAccount().addAll(byAccount.values());
        cs.getByCategory().addAll(byCategory.values());
        cs.setTotalInflow(scale2(totalIn));
        cs.setTotalOutflow(scale2(totalOut));
        cs.setTotalNet(scale2(totalIn.subtract(totalOut)));

        // 月末余额行:遍历真实账户(虚拟账户如"平台待结算"不计入现金合计)
        BigDecimal cashTotal = BigDecimal.ZERO;
        for (MoneyDtos.AccountRow a : accountService.list()) {
            BigDecimal opening = nz(a.getOpeningBalance());
            BigDecimal end = opening.add(netToMonthEnd.getOrDefault(a.getId(), BigDecimal.ZERO));
            MonthlyDtos.AccountBalanceRow row = new MonthlyDtos.AccountBalanceRow();
            row.setAccountId(a.getId());
            row.setAccountName(a.getAccountName());
            row.setAccountType(a.getAccountType());
            row.setOpeningBalance(scale2(opening));
            row.setMonthEndBalance(scale2(end));
            boolean virtual = Boolean.TRUE.equals(a.getIsVirtual());
            row.setVirtual(virtual);
            cs.getMonthEndBalances().add(row);
            if (!virtual) {
                cashTotal = cashTotal.add(end);
            }
        }
        cs.setMonthEndCashTotal(scale2(cashTotal));
        return cs;
    }

    private void accumulate(Map<String, MonthlyDtos.CashflowRow> map, String name, boolean in, BigDecimal amt) {
        MonthlyDtos.CashflowRow row = map.computeIfAbsent(name, k -> {
            MonthlyDtos.CashflowRow r = new MonthlyDtos.CashflowRow();
            r.setName(k);
            return r;
        });
        if (in) {
            row.setInflow(row.getInflow().add(amt));
        } else {
            row.setOutflow(row.getOutflow().add(amt));
        }
        row.setNet(row.getInflow().subtract(row.getOutflow()));
        row.setCount(row.getCount() + 1);
    }

    // ============================== ④ 往来表 ==============================

    public MonthlyDtos.PayableSummary payableSummary() {
        MonthlyDtos.PayableSummary ps = new MonthlyDtos.PayableSummary();
        BigDecimal payableTotal = BigDecimal.ZERO;
        BigDecimal prepayTotal = BigDecimal.ZERO;
        for (SettleDtos.SupplierOverviewRow s : payableService.overview()) {
            BigDecimal bal = nz(s.getBalance());
            if (bal.signum() == 0) {
                continue; // 只列有往来余额的供应商
            }
            ps.getSuppliers().add(s);
            if (bal.signum() > 0) {
                payableTotal = payableTotal.add(bal);
            } else {
                prepayTotal = prepayTotal.add(bal.abs());
            }
        }
        ps.setPayableTotal(scale2(payableTotal));
        ps.setPrepayTotal(scale2(prepayTotal));
        ps.setAging(payableService.payableAging());
        ps.setPlatformPending(settlementService.pendingAging());
        return ps;
    }

    // ============================== ⑤ 损耗报表 ==============================

    public MonthlyDtos.LossSummary lossSummary(String month) {
        MonthlyDtos.LossSummary ls = new MonthlyDtos.LossSummary();
        ls.setMonth(month);
        BigDecimal totalAmt = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal topAmt = null;
        for (StocktakeDtos.LossStatRow r : stocktakeService.lossStats(LOSS_LOOKBACK)) {
            if (!month.equals(r.getMonth())) {
                continue; // lossStats 返回近 N 月,这里只取本月
            }
            ls.getRows().add(r);
            BigDecimal amt = nz(r.getAmount());
            totalAmt = totalAmt.add(amt);
            totalQty = totalQty.add(nz(r.getQty()));
            if (topAmt == null || amt.compareTo(topAmt) > 0) {
                topAmt = amt;
                ls.setTopReason(r.getReason());
            }
        }
        ls.setTotalAmount(scale2(totalAmt));
        ls.setTotalQty(totalQty);
        return ls;
    }

    // ============================== ⑥ 资产快照 + 趋势 ==============================

    public MonthlyDtos.AssetSummary assetSummary() {
        MonthlyDtos.AssetSummary as = new MonthlyDtos.AssetSummary();
        as.setSnapshot(assetSnapshotService.current());
        List<FinReportDtos.SnapshotRow> trend = assetSnapshotService.trend(TREND_MONTHS);
        as.setTrend(trend == null ? new ArrayList<>() : trend);
        return as;
    }

    // ============================== 工具 ==============================

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static BigDecimal scale2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
