package top.aole.vend.modules.monthly.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.finreport.service.ProfitReportService;
import top.aole.vend.modules.monthly.dto.MonthlyDtos;
import top.aole.vend.modules.monthly.dto.MonthlyDtos.CalendarBoard;
import top.aole.vend.modules.monthly.dto.MonthlyDtos.CalendarDay;

import java.math.BigDecimal;

/**
 * 财务月度工作日历(§10.2):1-5 日各步骤状态看板。
 *
 * "自动完成 / 待人工" 由规则据本月真实数据判定(不是拍脑袋):
 * 1 日 月度盘点 → 需人工盘(系统只自动汇总差异);
 * 2 日 平台结算核对 → 有待结算余额=待人工,否则自动完成;
 * 3 日 核对报表包 → 报表包已自动生成(AUTO_DONE),待财务核对;
 * 5 日 月度经营分析报告 → AI 草稿自动生成(AUTO_DONE),待老板过目定改进任务。
 */
@Service
@RequiredArgsConstructor
public class MonthlyCalendarService {

    private final MonthlyReportService monthlyReportService;
    private final ProfitReportService profitReportService;

    public static final String AUTO_DONE = "AUTO_DONE";
    public static final String PENDING_MANUAL = "PENDING_MANUAL";

    public CalendarBoard board(String month) {
        // 统一口径月
        String period = profitReportService.monthly(month).getPeriod();
        CalendarBoard board = new CalendarBoard();
        board.setMonth(period);
        int pending = 0;

        // 本月损耗(判 1 日盘点是否有差异汇总)
        MonthlyDtos.LossSummary loss = monthlyReportService.lossSummary(period);
        boolean hasCountData = !loss.getRows().isEmpty();
        pending += add(board, 1, "月度盘点 SOP:货盘 + 钱盘",
                "前一天自动生成任务包;差异自动汇总", PENDING_MANUAL,
                hasCountData ? "本月已有盘点差异记录,差异已自动汇总,待财务确认盘点"
                        : "本月暂无盘点记录,需财务发起月度盘点");

        // 2 日 平台结算核对
        MonthlyDtos.PayableSummary payable = monthlyReportService.payableSummary();
        BigDecimal pendingBal = payable.getPlatformPending() == null ? BigDecimal.ZERO
                : nz(payable.getPlatformPending().getPendingBalance());
        boolean settleClean = pendingBal.signum() == 0;
        pending += add(board, 2, "平台结算核对:录到账 + 传凭证 → 核销;发对账单、付款清欠",
                "结算单预生成、差异自动算;对账单一键导出",
                settleClean ? AUTO_DONE : PENDING_MANUAL,
                settleClean ? "平台无待结算余额,本项已闭环"
                        : String.format("尚有 %s 元平台待结算,需人工录到账并核销", money(pendingBal)));

        // 3 日 核对报表包(报表包始终自动生成)
        pending += add(board, 3, "核对系统自动生成的月度报表包",
                "报表包自动生成,亮出待核对项", AUTO_DONE,
                "六件套报表已自动生成,待财务逐项核对");

        // 5 日 月度经营分析报告(AI 草稿自动生成)
        pending += add(board, 5, "和老板过《月度经营分析报告》,定下月改进任务",
                "AI 起草报告全文;改进任务入 PDCA 清单", AUTO_DONE,
                "AI 已起草七节经营分析报告草稿,待老板过目并定下月改进任务");

        board.setPendingCount(pending);
        return board;
    }

    private int add(CalendarBoard board, int day, String finance, String auto, String status, String note) {
        CalendarDay d = new CalendarDay();
        d.setDay(day);
        d.setFinanceAction(finance);
        d.setSystemAuto(auto);
        d.setStatus(status);
        d.setNote(note);
        board.getDays().add(d);
        return PENDING_MANUAL.equals(status) ? 1 : 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String money(BigDecimal v) {
        return nz(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
