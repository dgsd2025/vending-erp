package top.aole.vend.modules.money.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.period.service.PeriodLockService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资金流水写手(包私有):cash_flow 的**唯一写入通道**(附录D 铁三条之一)。
 *
 * 刻意 **不是 public 类**(抄 StockLedgerWriter 的纪律)——付款/结算/调整/索赔等
 * 业务模块只能通过发布 {@link MoneyPostingEvent}(单据确认事件)间接触发写入
 * (MoneyPostingListener),全系统不存在"绕开单据直改钱账"的公开方法。
 *
 * 写手强制校验:
 * ① 关联单据必填(refDocType/refDocId,钱只能被单据改 §9.1);
 * ② direction 收/支、amount>0、category 必填;
 * ③ pl_line 由 category 唯一推导(每类流水有且只有一个利润表去处,写入方不许自报);
 * ④ 虚拟账户不可手工收支(category=资金调整 打到虚拟账户 → 拒绝);
 * ⑤ book_period 按锁账口径推导:业务月已锁 → 记当月(P0-2,同 sale_record)。
 * ⑥ 非现金行(M3-9:account_id=NULL,只进利润表行不动账户余额)只允许白名单类别
 *   (成本调整已售Δ / 结算差异收口),其余一律要求真实账户。
 */
@Component
@RequiredArgsConstructor
class CashFlowWriter {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong SEQ = new AtomicLong();

    /** 允许 account_id=NULL 的"非现金过账"类别白名单(M3-9 P1-4/P1-5) */
    private static final java.util.Set<CashFlowCategory> CASHLESS_ALLOWED = java.util.EnumSet.of(
            CashFlowCategory.COST_ADJUST_FLOW,
            CashFlowCategory.SETTLE_DIFF_LOSS,
            CashFlowCategory.SETTLE_DIFF_GAIN);

    private final CashFlowMapper cashFlowMapper;
    private final AccountMapper accountMapper;
    private final PeriodLockService periodLockService;

    /** 过账一张单据的全部流水行(同事务,任何一行校验失败整体回滚) */
    List<CashFlow> post(MoneyPostingEvent event) {
        if (event.getRefDocType() == null || event.getRefDocType().trim().isEmpty()
                || event.getRefDocId() == null) {
            throw new BizException("资金流水必须关联来源单据(refDocType/refDocId):钱不允许直接记一笔(§9.1)");
        }
        if (event.getLines().isEmpty()) {
            throw new BizException("钱账过账事件[" + event.getRefDocType() + ":" + event.getRefDocId() + "]没有流水行");
        }
        List<CashFlow> written = new ArrayList<>();
        String currentMonth = YearMonth.now().format(PERIOD);
        for (MoneyPostingEvent.Line line : event.getLines()) {
            written.add(writeLine(event, line, currentMonth));
        }
        return written;
    }

    private CashFlow writeLine(MoneyPostingEvent event, MoneyPostingEvent.Line line, String currentMonth) {
        if (!CashFlow.DIR_IN.equals(line.getDirection()) && !CashFlow.DIR_OUT.equals(line.getDirection())) {
            throw new BizException("流水方向只能是 收/支:" + line.getDirection());
        }
        if (line.getAmount() == null || line.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("流水金额必须为正数(方向由 收/支 表达):" + line.getAmount());
        }
        CashFlowCategory category = line.getCategory();
        if (category == null) {
            throw new BizException("流水类别(category)必填——利润表去处由类别唯一推导");
        }
        Long accountId;
        if (line.getAccountId() == null) {
            // 非现金过账(M3-9):白名单类别才许无账户——只进利润表行,不动任何账户余额
            if (!CASHLESS_ALLOWED.contains(category)) {
                throw new BizException("流水类别「" + category.getLabel()
                        + "」必须挂真实账户:非现金过账(account_id=NULL)只允许 成本调整/结算差异 类别(M3-9)");
            }
            accountId = null;
        } else {
            Account account = accountMapper.selectById(line.getAccountId());
            if (account == null) {
                throw new BizException("资金账户不存在:id=" + line.getAccountId());
            }
            if (account.getStatus() != null && account.getStatus() == 0) {
                throw new BizException("账户「" + account.getAccountName() + "」已停用,不能再走流水");
            }
            if (Boolean.TRUE.equals(account.getIsVirtual()) && category == CashFlowCategory.CASH_ADJUST) {
                throw new BizException("虚拟账户「" + account.getAccountName()
                        + "」不可手工收支:其余额永远由业务单据推算,钱盘差异只调真实账户(资金调整单)");
            }
            accountId = account.getId();
        }
        LocalDateTime bizTime = line.getBizTime() == null ? LocalDateTime.now() : line.getBizTime();
        String bizPeriod = bizTime.format(PERIOD);

        CashFlow flow = new CashFlow();
        flow.setFlowNo("CF-" + LocalDateTime.now().format(NO_TS) + "-"
                + String.format("%04d", SEQ.incrementAndGet() % 10000));
        flow.setAccountId(accountId);
        flow.setDirection(line.getDirection());
        flow.setAmount(line.getAmount());
        flow.setCategory(category.getLabel());
        // 每类流水有且只有一个去处:pl_line 只在这里落值,来源只有枚举映射
        flow.setPlLine(category.getPlLine().getLabel());
        flow.setRefDocType(event.getRefDocType());
        flow.setRefDocId(event.getRefDocId());
        flow.setBizTime(bizTime);
        flow.setBookPeriod(periodLockService.isLocked(bizPeriod) ? currentMonth : bizPeriod);
        flow.setRemark(line.getRemark());
        flow.setCreateUser(event.getOperator());
        cashFlowMapper.insert(flow);
        return flow;
    }
}
