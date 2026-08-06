package top.aole.vend.modules.stocktake.money.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.stocktake.money.domain.entity.CashAdjust;
import top.aole.vend.modules.stocktake.money.domain.enums.CashAdjustReason;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.AdjustCreateReq;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.AdjustRow;
import top.aole.vend.modules.stocktake.money.mapper.CashAdjustMapper;
import top.aole.vend.modules.stocktake.money.mapper.CashMoneyQueryMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资金调整单(M3-5,审计 P1-7:钱盘差异的**唯一出口**;期初修正一律走此通道)。
 *
 * 复用 doc 通道(DocType.CASH_ADJUST 十枚举已有):
 *   建单(账户/金额±/原因枚举,其他必备注)→ 提交(待确认)→ **老板确认**(X-User-Role 角色头,
 *   同红冲/盘点>¥50 一把尺子)→ docService.confirm 走 DocStatusGuard 条件更新防双击 →
 *   同事务发 MoneyPostingEvent → CashFlowWriter 落 cash_flow(category=资金调整,
 *   pl_line=不进利润表;虚拟账户拒绝真实放行,M3-1 写手内置)→ 单据自动完成。
 *
 * 钱只能被单据改(§9.1):本服务没有任何直改余额的路径,余额=期初+Σ流水自然修正。
 */
@Service
@RequiredArgsConstructor
public class CashAdjustService {

    /** cash_flow.ref_doc_type 固定值(V1.0.0 注释口径:资金调整单) */
    public static final String REF_DOC_TYPE = "资金调整单";

    private final DocService docService;
    private final DocHeadMapper docHeadMapper;
    private final CashAdjustMapper cashAdjustMapper;
    private final CashMoneyQueryMapper queryMapper;
    private final AccountMapper accountMapper;
    private final PeriodLockService periodLockService;
    private final OpLogService opLogService;
    private final ApplicationEventPublisher eventPublisher;

    // ============================== 建单 ==============================

    /**
     * 建单并提交(草稿→待确认):账户/带符号金额/原因枚举全量校验;
     * 虚拟账户建单即拦(不等到过账才报错,前端少跑一轮)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(AdjustCreateReq req, Long userId, String operator) {
        if (req.getAdjustAmount() == null || req.getAdjustAmount().compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("调整金额不能为 0(带符号:+ 实际多于系统收入,− 实际少于系统支出)");
        }
        Account account = mustGetAccount(req.getAccountId());
        if (Boolean.TRUE.equals(account.getIsVirtual())) {
            throw new BizException("虚拟账户「" + account.getAccountName()
                    + "」不可手工收支:其余额永远由业务单据推算,钱盘差异只调真实账户");
        }
        if (account.getStatus() != null && account.getStatus() == 0) {
            throw new BizException("账户「" + account.getAccountName() + "」已停用,不能再走流水");
        }
        String direction = req.getAdjustAmount().signum() > 0 ? CashAdjust.DIR_IN : CashAdjust.DIR_OUT;
        CashAdjustReason reason = CashAdjustReason.ofLabel(StrUtil.trim(req.getReason()));
        reason.assertDirection(direction);
        if (reason.isRemarkRequired() && StrUtil.isBlank(req.getRemark())) {
            throw new BizException("原因「" + reason.getLabel() + "」必须填写备注说明(留痕才许兜底)");
        }
        BigDecimal amount = req.getAdjustAmount().abs();

        // 单据头:复用 doc 通道(状态机/op_log/不许删全套),不挂商品明细(只动钱不动货)
        DocHead head = new DocHead();
        head.setDocNo(docService.nextDocNo(DocType.CASH_ADJUST, LocalDate.now()));
        head.setDocType(DocType.CASH_ADJUST);
        head.setDocStatus(DocStatus.DRAFT);
        head.setBizDate(LocalDate.now());
        head.setDocSource(DocService.SOURCE_MANUAL);
        head.setNegStockExempt(false);
        head.setTotalQty(BigDecimal.ZERO);
        head.setTotalAmount(amount);
        StringBuilder remark = new StringBuilder(String.format("资金调整:账户「%s」%s ¥%s(原因:%s)",
                account.getAccountName(), direction, amount.stripTrailingZeros().toPlainString(),
                reason.getLabel()));
        if (StrUtil.isNotBlank(req.getRemark())) {
            remark.append("。").append(req.getRemark().trim());
        }
        head.setRemark(remark.length() > 490 ? remark.substring(0, 490) : remark.toString());
        head.setCreateUser(userId);
        docHeadMapper.insert(head);

        CashAdjust adjust = new CashAdjust();
        adjust.setDocId(head.getId());
        adjust.setAccountId(account.getId());
        adjust.setDirection(direction);
        adjust.setAmount(amount);
        adjust.setReason(reason.getLabel());
        adjust.setCashCheckId(req.getCashCheckId());
        adjust.setCreateUser(userId);
        cashAdjustMapper.insert(adjust);

        opLogService.record(operator, "新建资金调整单", "doc_head", head.getId(), null,
                JSONUtil.toJsonStr(adjust));
        docService.submit(head.getId(), userId); // 草稿 → 待确认(老板确认是下一步)
        return head.getId();
    }

    // ============================== 老板确认 → 过账落流水 ==============================

    /**
     * 老板确认并过账:
     * ① assertBossRole(X-User-Role 占位头,同红冲/解锁一把尺子);
     * ② docService.confirm → DocStatusGuard 条件更新(待确认→已确认),并发双击后到者抛"已被他人处理";
     * ③ 同事务发 MoneyPostingEvent → CashFlowWriter 落 cash_flow(失败整体回滚);
     * ④ 单据自动流转"已完成"(资金调整只有一步钱账动作,过完即闭环)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long docId, Long userId, String role, String operator) {
        periodLockService.assertBossRole(role, "资金调整确认");
        DocHead head = docHeadMapper.selectById(docId);
        if (head == null) {
            throw new BizException("资金调整单不存在:" + docId);
        }
        if (head.getDocType() != DocType.CASH_ADJUST) {
            throw new BizException(String.format("单据[%s]类型为[%s],不是资金调整单",
                    head.getDocNo(), head.getDocType().getLabel()));
        }
        CashAdjust adjust = mustGetExt(docId);
        Account account = mustGetAccount(adjust.getAccountId());

        // 防双击/并发:confirm 内部 DocStatusGuard 条件更新,后到者在这里抛异常整体回滚
        docService.confirm(docId, userId, false, null);

        // 钱只能被单据改:同事务发事件,唯一写手落流水(虚拟拒绝真实放行,写手兜底再验一遍)
        MoneyPostingEvent event = new MoneyPostingEvent(REF_DOC_TYPE, docId, userId);
        String remark = String.format("%s 资金调整(%s)", head.getDocNo(), adjust.getReason());
        if (CashAdjust.DIR_IN.equals(adjust.getDirection())) {
            event.inflow(account.getId(), adjust.getAmount(), CashFlowCategory.CASH_ADJUST,
                    LocalDateTime.now(), remark);
        } else {
            event.outflow(account.getId(), adjust.getAmount(), CashFlowCategory.CASH_ADJUST,
                    LocalDateTime.now(), remark);
        }
        eventPublisher.publishEvent(event);

        docService.complete(docId, userId); // 已确认 → 已完成(钱账动作已闭环)
        opLogService.record(operator, "老板确认资金调整(落流水)", "doc_head", docId,
                null, adjust.getDirection() + " ¥" + adjust.getAmount());
    }

    // ============================== 查询 ==============================

    public List<AdjustRow> list(int limit) {
        return queryMapper.adjustRows(limit <= 0 ? 50 : limit);
    }

    /** 单条详情(核对页回显/单据抽屉补充) */
    public AdjustRow detail(Long docId) {
        return queryMapper.adjustRows(Integer.MAX_VALUE).stream()
                .filter(r -> docId.equals(r.getDocId())).findFirst()
                .orElseThrow(() -> new BizException("资金调整单不存在:" + docId));
    }

    // ============================== 内部 ==============================

    CashAdjust mustGetExt(Long docId) {
        CashAdjust adjust = cashAdjustMapper.selectOne(new LambdaQueryWrapper<CashAdjust>()
                .eq(CashAdjust::getDocId, docId).last("LIMIT 1"));
        if (adjust == null) {
            throw new BizException("资金调整单缺扩展记录(账户/金额/原因):doc=" + docId
                    + "。请走「新建资金调整单」入口建单,不要用通用单据接口裸建");
        }
        return adjust;
    }

    private Account mustGetAccount(Long accountId) {
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException("资金账户不存在:id=" + accountId);
        }
        return account;
    }
}
