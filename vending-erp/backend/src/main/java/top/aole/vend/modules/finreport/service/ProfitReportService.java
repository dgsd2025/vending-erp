package top.aole.vend.modules.finreport.service;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.service.ClaimService;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.mapper.FinReportQueryMapper;
import top.aole.vend.modules.money.domain.enums.PlLine;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.CashFlowQueryService;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.report.service.CostEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 简版利润表(M3-6,§13.1 效力最高):
 *
 * 销售收入 − 销售成本 = 毛利;毛利 − 平台手续费 − 杂费 − 损耗 ± 成本调整
 * + 其他收入(赔付/平台外/补贴)± 上期调整 = 经营利润。
 *
 * 取数口径:
 * - 销售收入/成本:按 **入账月 book_period** 聚合(锁账口径);收入=正常+退款(负),
 *   兑换/线下补录收入 0;成本= CostEngine 移动加权按 saleId 结转(兑换也计成本,不依赖回写);
 *   biz_period≠book_period 的补导行拆出 → "上期调整"行承接(旧报表永不重算,P0-2);
 * - 平台手续费/杂费/成本调整/其他收入三细类:cash_flow pl_line×月聚合
 *   ({@link CashFlowQueryService#plMonthlySummary}),类别↔利润表行一一映射(附录D);
 * - 损耗:已确认盘亏/报损单成本额({@link ClaimService#netShrinkage}),行内注净损耗=损耗−已获赔
 *   (赔付进"其他收入-赔付"行,不重复计:−损耗+赔付 ≡ −净损耗+0);
 * - 锁账月:标"已归档不重算"(锁定期单据禁改+补导入当月,数字天然定格);
 * - lock_diff_note:已核销结算单区间遇锁后补导 → 只写提示列不改状态(P0-2)。
 */
@Service
@RequiredArgsConstructor
public class ProfitReportService {

    private static final Pattern PERIOD_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final FinReportQueryMapper queryMapper;
    private final CostEngine costEngine;
    private final CashFlowQueryService cashFlowQueryService;
    private final ClaimService claimService;
    private final PeriodLockService periodLockService;
    private final SettleModeService settleModeService;

    // ============================== 月度利润表 ==============================

    @Transactional(rollbackFor = Exception.class)
    public FinReportDtos.ProfitResp monthly(String period) {
        FinReportDtos.ProfitResp resp = new FinReportDtos.ProfitResp();
        List<String> months = queryMapper.bookPeriods();
        resp.setMonths(months);
        if (StrUtil.isBlank(period)) {
            period = months.isEmpty() ? YearMonth.now().format(PERIOD) : months.get(months.size() - 1);
        }
        assertPeriodFormat(period);
        resp.setPeriod(period);

        MoneyDtos.SettleModeResp mode = settleModeService.get();
        resp.setSettleMode(mode.getMode());
        resp.setSettleBanner(mode.getBanner());

        // ---- 销售侧:book_period 聚合 + 补导拆行(上期调整) ----
        CostEngine.Replay replay = costEngine.replay();
        BigDecimal revMain = BigDecimal.ZERO;
        BigDecimal costMain = BigDecimal.ZERO;
        BigDecimal revAdj = BigDecimal.ZERO;
        BigDecimal costAdj = BigDecimal.ZERO;
        int adjCount = 0;
        int noCostCount = 0;
        for (Map<String, Object> raw : queryMapper.salesByBookPeriod(period)) {
            String type = String.valueOf(raw.get("orderType"));
            if ("测试".equals(type)) {
                continue; // 三口径:测试不计(§13.2-3)
            }
            boolean zeroRevenue = "兑换".equals(type) || "线下补录".equals(type);
            BigDecimal rev = zeroRevenue ? BigDecimal.ZERO
                    : nz((BigDecimal) raw.get("amountReceived"));
            Long saleId = ((Number) raw.get("id")).longValue();
            BigDecimal cost = replay.getSaleCost().get(saleId);
            boolean noCost = cost == null && raw.get("productId") != null
                    && replay.getNoCostSaleIds().contains(saleId);
            String biz = String.valueOf(raw.get("bizPeriod"));
            String book = String.valueOf(raw.get("bookPeriod"));
            if (!Objects.equals(biz, book)) {
                // 锁后补导:入账当月,上期调整行承接(P0-2)
                adjCount++;
                revAdj = revAdj.add(rev);
                if (cost != null) {
                    costAdj = costAdj.add(cost);
                }
            } else {
                revMain = revMain.add(rev);
                if (cost != null) {
                    costMain = costMain.add(cost);
                }
            }
            if (noCost) {
                noCostCount++;
            }
        }
        BigDecimal gross = revMain.subtract(costMain);
        BigDecimal priorAdjust = revAdj.subtract(costAdj);

        // ---- 资金流水侧:pl_line × 当月净额(收−支) ----
        Map<String, BigDecimal> lineNet = new HashMap<>();
        for (MoneyDtos.PlSummaryRow row : cashFlowQueryService.plMonthlySummary(period, period)) {
            lineNet.merge(row.getPlLine(), row.getNet(), BigDecimal::add);
        }
        BigDecimal fee = net(lineNet, PlLine.PLATFORM_FEE);
        BigDecimal misc = net(lineNet, PlLine.MISC_EXPENSE);
        BigDecimal costAdjustFlow = net(lineNet, PlLine.COST_ADJUST);
        BigDecimal claimIncome = net(lineNet, PlLine.OTHER_INCOME_CLAIM);
        BigDecimal offlineIncome = net(lineNet, PlLine.OTHER_INCOME_OFFLINE);
        BigDecimal subsidyIncome = net(lineNet, PlLine.OTHER_INCOME_SUBSIDY);
        BigDecimal shrinkFlow = net(lineNet, PlLine.SHRINKAGE);
        resp.setNonPlNet(scale2(net(lineNet, PlLine.NON_PL)));

        // ---- 损耗:盘亏/报损单成本额(+流水侧损耗净额兜底),注净损耗 ----
        ClaimDtos.NetShrinkageResp shrink = claimService.netShrinkage(period, period);
        BigDecimal lossRow = nz(shrink.getLossAmount()).subtract(shrinkFlow); // shrinkFlow 支出为负净额
        BigDecimal netLoss = lossRow.subtract(nz(shrink.getCompensatedAmount()));

        // ---- 行装配(顺序=§13.1;amount 一律为对经营利润的带符号贡献) ----
        addRow(resp, "salesIncome", PlLine.SALES_INCOME.getLabel(), revMain, false,
                "正常单实收 + 退款(负);兑换/线下收入不在此行(兑换走补贴、线下走平台外)");
        addRow(resp, "salesCost", PlLine.SALES_COST.getLabel(), costMain.negate(), false,
                "移动加权成本结转(兑换出货也计成本)"
                        + (noCostCount > 0 ? ";⚠️ " + noCostCount + " 笔无采购史销售未计成本" : ""));
        addRow(resp, "grossProfit", "毛利", gross, true, "毛利 = 实收金额 − 移动加权成本(§13.1 定死)");
        addRow(resp, "platformFee", PlLine.PLATFORM_FEE.getLabel(), fee, false,
                "平台这个月吃掉了我多少钱(平台结算单核销时入账)");
        addRow(resp, "miscExpense", PlLine.MISC_EXPENSE.getLabel(), misc, false,
                "电费/维修/杂支/设备购置(设备另进台账,现金支出记杂费)");
        addRow(resp, "shrinkage", PlLine.SHRINKAGE.getLabel(), lossRow.negate(), false,
                String.format("已确认盘亏/报损成本额;已获赔 %s → 净损耗 %s(赔付列在下方其他收入行,不重复计)",
                        plain(nz(shrink.getCompensatedAmount())), plain(netLoss)));
        addRow(resp, "costAdjust", PlLine.COST_ADJUST.getLabel(), costAdjustFlow, false,
                "单价录错的已售部分在此承接,不追溯改历史(P0-1)");
        addRow(resp, "otherIncomeClaim", PlLine.OTHER_INCOME_CLAIM.getLabel(), claimIncome, false,
                "索赔到账(吞货/失窃追回来的钱)");
        addRow(resp, "otherIncomeOffline", PlLine.OTHER_INCOME_OFFLINE.getLabel(), offlineIncome, false,
                "机器故障线下直收等平台外收入");
        addRow(resp, "otherIncomeSubsidy", PlLine.OTHER_INCOME_SUBSIDY.getLabel(), subsidyIncome, false,
                "厂家兑换/活动补贴到账");
        addRow(resp, "priorAdjust", "上期调整", priorAdjust, false,
                adjCount == 0 ? "本月无锁账后补导"
                        : String.format("锁账后补导 %d 笔:收入 %s − 成本 %s(旧月份报表永不重算,由本行承接,P0-2)",
                        adjCount, plain(revAdj), plain(costAdj)));

        BigDecimal operating = gross.add(fee).add(misc).subtract(lossRow).add(costAdjustFlow)
                .add(claimIncome).add(offlineIncome).add(subsidyIncome).add(priorAdjust);
        addRow(resp, "operatingProfit", "经营利润", operating, true,
                "毛利 − 平台手续费 − 杂费 − 损耗 ± 成本调整 + 其他收入 ± 上期调整(§13.1)");
        resp.setOperatingProfit(scale2(operating));

        // ---- 锁账标记 + lock_diff_note(已核销结算单只提示不改状态) ----
        if (periodLockService.isLocked(period)) {
            resp.setLocked(true);
            resp.setLockedNote(String.format(
                    "%s 已锁账(锁账线 %s):本表已归档不重算——锁定期单据禁改,锁后补导一律入当月「上期调整」行",
                    period, periodLockService.lockLine()));
        }
        refreshLockDiffNotes();
        for (Map<String, Object> raw : queryMapper.verifiedSettlements()) {
            Object note = raw.get("lockDiffNote");
            if (note == null || StrUtil.isBlank(String.valueOf(note))) {
                continue;
            }
            FinReportDtos.LockDiffRow row = new FinReportDtos.LockDiffRow();
            row.setSettlementId(((Number) raw.get("id")).longValue());
            row.setStmtNo(String.valueOf(raw.get("stmtNo")));
            row.setPeriodStart(String.valueOf(raw.get("periodStart")));
            row.setPeriodEnd(String.valueOf(raw.get("periodEnd")));
            row.setStlStatus(String.valueOf(raw.get("stlStatus")));
            row.setNote(String.valueOf(note));
            resp.getLockDiffNotes().add(row);
        }
        return resp;
    }

    // ============================== lock_diff_note 刷新 ==============================

    /**
     * 已核销结算单 × 锁后补导命中检测(P0-2):
     * 补导行特征 = biz_period≠book_period 且未回填 settlement_id;命中已核销单区间
     * → 只写 lock_diff_note 提示,stl_status 永不回退;差异消失(如后续被结算)→ 清提示。
     * 幂等:内容没变不落 UPDATE。
     */
    @Transactional(rollbackFor = Exception.class)
    public int refreshLockDiffNotes() {
        int updated = 0;
        for (Map<String, Object> raw : queryMapper.verifiedSettlements()) {
            Long id = ((Number) raw.get("id")).longValue();
            LocalDate start = toDate(raw.get("periodStart"));
            LocalDate end = toDate(raw.get("periodEnd"));
            Map<String, Object> hit = queryMapper.backfillHitInRange(start, end);
            long cnt = ((Number) hit.get("cnt")).longValue();
            BigDecimal amount = new BigDecimal(String.valueOf(hit.get("amount")));
            String note = cnt == 0 ? null : String.format(
                    "锁后新增差异:结算区间 %s~%s 内补导 %d 笔销售(¥%s)未含在本单,两差口径可能变化;仅提示,状态不变(P0-2)",
                    start, end, cnt, plain(amount));
            String existing = raw.get("lockDiffNote") == null ? null : String.valueOf(raw.get("lockDiffNote"));
            if (!Objects.equals(note, existing)) {
                updated += queryMapper.updateSettlementLockDiffNote(id, note);
            }
        }
        return updated;
    }

    // ============================== 内部 ==============================

    private void addRow(FinReportDtos.ProfitResp resp, String key, String label,
                        BigDecimal amount, boolean subtotal, String note) {
        FinReportDtos.PlRow row = new FinReportDtos.PlRow();
        row.setKey(key);
        row.setLabel(label);
        row.setAmount(scale2(amount));
        row.setSubtotal(subtotal);
        row.setNote(note);
        resp.getRows().add(row);
    }

    private static LocalDate toDate(Object raw) {
        if (raw instanceof LocalDate) {
            return (LocalDate) raw;
        }
        if (raw instanceof Date) {
            return ((Date) raw).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(raw));
    }

    private static BigDecimal net(Map<String, BigDecimal> lineNet, PlLine line) {
        return lineNet.getOrDefault(line.getLabel(), BigDecimal.ZERO);
    }

    private void assertPeriodFormat(String period) {
        if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
            throw new BizException("月份格式必须为 YYYY-MM:" + period);
        }
        try {
            YearMonth.parse(period, PERIOD);
        } catch (Exception e) {
            throw new BizException("非法月份:" + period);
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
