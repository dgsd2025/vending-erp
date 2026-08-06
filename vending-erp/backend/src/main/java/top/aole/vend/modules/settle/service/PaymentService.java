package top.aole.vend.modules.settle.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.settle.domain.entity.Payment;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.PaymentMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 付款单服务(§9.2 确认点③④):
 *
 * - 录入:三要素 付给谁/从哪个账户/多少钱 + 可选核销结算单(不强制逐单;无单=预付/冲余额);
 * - **凭证硬门禁**(§9.1-4):attachment(refType=payment) countOf=0 → 拒绝进"已付款";
 * - 确认付款 = 发布 MoneyPostingEvent(SUPPLIER_PAYMENT,本金往来不进利润表)同事务落 cash_flow
 *   ——钱只能被单据改,写手校验失败整体回滚;
 * - 自动核销:金额=结算单实结 → 付款[结算完成]·结算单[已完成]·采购单[待结算→已结算],全链闭环;
 *   金额≠实结 → 付款与结算单双双[差异挂起](红灯),补说明(resolveDiff)或改单后闭环。
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PaymentMapper paymentMapper;
    private final SettleBillMapper billMapper;
    private final SettleBillService settleBillService;
    private final AccountMapper accountMapper;
    private final AttachmentService attachmentService;
    private final DocService docService;
    private final OpLogService opLogService;
    private final ApplicationEventPublisher eventPublisher;

    // ============================== 录入 ==============================

    @Transactional(rollbackFor = Exception.class)
    public Long create(SettleDtos.PaymentCreateReq req, Long userId, String operator) {
        Account account = accountMapper.selectById(req.getAccountId());
        if (account == null) {
            throw new BizException("付款账户不存在:id=" + req.getAccountId());
        }
        if (Boolean.TRUE.equals(account.getIsVirtual())) {
            throw new BizException("账户「" + account.getAccountName()
                    + "」是虚拟账户(余额由业务单据推算),付供应商货款只能走真实账户");
        }
        BigDecimal dedAmount = BigDecimal.ZERO;
        if (req.getSettleBillId() != null) {
            SettleBill bill = settleBillService.mustGet(req.getSettleBillId());
            if (!SettleBill.DIR_NORMAL.equals(bill.getDirection())) {
                throw new BizException("红字结算单不收付款,只能冲抵下一张正常单");
            }
            if (!bill.getSupplierId().equals(req.getSupplierId())) {
                throw new BizException(String.format("结算单[%s]属于供应商[%d],与付款对象[%d]不符",
                        bill.getBillNo(), bill.getSupplierId(), req.getSupplierId()));
            }
            if (!SettleBill.ST_PENDING_PAY.equals(bill.getBillStatus())
                    && !SettleBill.ST_DIFF.equals(bill.getBillStatus())) {
                throw new BizException(String.format("结算单[%s]状态为[%s],仅[待付款/差异挂起]可关联付款(待确认请先老板复核)",
                        bill.getBillNo(), bill.getBillStatus()));
            }
            dedAmount = bill.getDeductionAmount() == null ? BigDecimal.ZERO : bill.getDeductionAmount();
        }
        Payment payment = new Payment();
        payment.setPayNo(nextPayNo());
        payment.setSupplierId(req.getSupplierId());
        payment.setAccountId(req.getAccountId());
        payment.setAmount(req.getAmount());
        payment.setDeductionAmount(dedAmount);
        payment.setSettleBillId(req.getSettleBillId());
        payment.setPayStatus(Payment.ST_PENDING);
        payment.setRemark(req.getRemark());
        payment.setCreateUser(userId);
        paymentMapper.insert(payment);
        opLogService.record(operator, "新建付款单", "payment", payment.getId(), null,
                String.format("供应商%d 账户%s 金额%s%s", req.getSupplierId(), account.getAccountName(),
                        req.getAmount(), req.getSettleBillId() == null ? "(不核销/预付)" : " 核销结算单" + req.getSettleBillId()));
        return payment.getId();
    }

    // ============================== 确认付款(凭证硬门禁 + 落流水 + 自动核销) ==============================

    @Transactional(rollbackFor = Exception.class)
    public Payment confirm(Long payId, Long userId, String operator) {
        Payment payment = mustGet(payId);
        if (!Payment.ST_PENDING.equals(payment.getPayStatus())) {
            throw new BizException(String.format("付款单[%s]状态为[%s],仅[待付款]可确认(重复确认被拒,幂等)",
                    payment.getPayNo(), payment.getPayStatus()));
        }
        // 凭证硬门禁(§9.1-4):无转账截图不能进"已付款"
        if (attachmentService.countOf("payment", payId) == 0) {
            throw new BizException(String.format(
                    "付款单[%s]没有转账凭证:请先上传转账截图(凭证强制,无凭证不能进入已付款,§9.1)", payment.getPayNo()));
        }
        // 确认时重查结算单当前状态(M3-9 P0-1,先查后抢占——拒付时不留任何写痕):
        // 建付款单后结算单可能已被红冲连锁作废/已核销——不在[待付款/差异挂起]一律拒付,
        // 防止给已退货的采购付钱、把已作废单改"已完成"复活
        if (payment.getSettleBillId() != null) {
            SettleBill current = settleBillService.mustGet(payment.getSettleBillId());
            if (!SettleBill.ST_PENDING_PAY.equals(current.getBillStatus())
                    && !SettleBill.ST_DIFF.equals(current.getBillStatus())) {
                throw new BizException(String.format(
                        "结算单[%s]当前状态为[%s],不能再付款:已被红冲连锁作废/已核销的结算单不许复活,请作废本付款单[%s]",
                        current.getBillNo(), current.getBillStatus(), payment.getPayNo()));
            }
        }
        // 条件更新抢占(M3-9 P0-1,仿 SettlementService 待核对→核销中):
        // 双开页面/双人并发确认同一张付款单,只允许一个通过——否则两笔流水双扣账户余额
        int claimed = paymentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Payment>()
                .eq(Payment::getId, payId)
                .eq(Payment::getPayStatus, Payment.ST_PENDING)
                .set(Payment::getPayStatus, Payment.ST_CONFIRMING));
        if (claimed != 1) {
            throw new BizException(String.format("付款单[%s]已被他人处理(并发确认防双扣),请刷新查看", payment.getPayNo()));
        }

        LocalDateTime now = LocalDateTime.now();
        payment.setPayTime(now);
        payment.setConfirmBy(userId);
        payment.setConfirmAt(now);
        payment.setBookPeriod(now.format(PERIOD));

        // 钱只能被单据改:发布过账事件(同事务,写手校验失败整体回滚)
        MoneyPostingEvent event = new MoneyPostingEvent("付款单", payment.getId(), userId);
        event.outflow(payment.getAccountId(), payment.getAmount(), CashFlowCategory.SUPPLIER_PAYMENT,
                now, "付供应商货款 " + payment.getPayNo());
        eventPublisher.publishEvent(event);

        String chain;
        if (payment.getSettleBillId() == null) {
            payment.setPayStatus(Payment.ST_SETTLED);
            chain = "不核销结算单(预付/冲余额),应付余额实时反映";
        } else {
            SettleBill bill = settleBillService.mustGet(payment.getSettleBillId());
            if (payment.getAmount().compareTo(bill.getAmountActual()) == 0) {
                // 金额核对通过 → 自动核销,全链闭环(§9.1-5)
                payment.setPayStatus(Payment.ST_SETTLED);
                bill.setBillStatus(SettleBill.ST_DONE);
                bill.setUpdateUser(userId);
                billMapper.updateById(bill);
                markDocSettled(bill, userId);
                chain = String.format("金额=实结%s核对通过:结算单[%s]已完成,采购单已结算,全链闭环", bill.getAmountActual(), bill.getBillNo());
            } else {
                // 差异处理:红灯挂起,补说明或改单(§9.2)
                payment.setPayStatus(Payment.ST_DIFF);
                bill.setBillStatus(SettleBill.ST_DIFF);
                bill.setDiffNote(String.format("付款[%s]实付%s ≠ 实结%s,差%s:待补说明或改单",
                        payment.getPayNo(), payment.getAmount(), bill.getAmountActual(),
                        payment.getAmount().subtract(bill.getAmountActual())));
                bill.setUpdateUser(userId);
                billMapper.updateById(bill);
                chain = "凭证金额≠结算单实结 → 差异挂起(红灯):" + bill.getDiffNote();
            }
        }
        payment.setUpdateUser(userId);
        paymentMapper.updateById(payment);
        opLogService.record(operator, "确认付款", "payment", payment.getId(), null,
                String.format("金额%s 落流水(供应商付款/不进利润表);%s", payment.getAmount(), chain));
        return payment;
    }

    // ============================== 差异挂起处理(补说明闭环) ==============================

    @Transactional(rollbackFor = Exception.class)
    public Payment resolveDiff(Long payId, String note, Long userId, String operator) {
        Payment payment = mustGet(payId);
        if (!Payment.ST_DIFF.equals(payment.getPayStatus())) {
            throw new BizException(String.format("付款单[%s]状态为[%s],不在差异挂起", payment.getPayNo(), payment.getPayStatus()));
        }
        payment.setPayStatus(Payment.ST_SETTLED);
        payment.setRemark((payment.getRemark() == null ? "" : payment.getRemark() + " | ") + "差异说明:" + note);
        payment.setUpdateUser(userId);
        paymentMapper.updateById(payment);

        if (payment.getSettleBillId() != null) {
            SettleBill bill = settleBillService.mustGet(payment.getSettleBillId());
            if (SettleBill.ST_DIFF.equals(bill.getBillStatus())) {
                bill.setBillStatus(SettleBill.ST_DONE);
                bill.setDiffNote((bill.getDiffNote() == null ? "" : bill.getDiffNote() + " → ") + "已补说明:" + note);
                bill.setUpdateUser(userId);
                billMapper.updateById(bill);
                markDocSettled(bill, userId);
            }
        }
        opLogService.record(operator, "差异处理-补说明闭环", "payment", payment.getId(), null, note);
        return payment;
    }

    // ============================== 内部 ==============================

    public Payment mustGet(Long payId) {
        Payment payment = paymentMapper.selectById(payId);
        if (payment == null) {
            throw new BizException("付款单不存在:id=" + payId);
        }
        return payment;
    }

    /** 采购单 待结算→已结算(全链闭环;老流程直付无"待结算"态时先补推进) */
    private void markDocSettled(SettleBill bill, Long userId) {
        if (bill.getSourceDocId() == null) {
            return;
        }
        DocStatus docStatus = docService.getDoc(bill.getSourceDocId()).getHead().getDocStatus();
        if (docStatus == DocStatus.CONFIRMED) {
            docService.markPendingSettle(bill.getSourceDocId(), userId);
            docStatus = DocStatus.PENDING_SETTLE;
        }
        if (docStatus == DocStatus.PENDING_SETTLE) {
            docService.markSettled(bill.getSourceDocId(), userId);
        }
    }

    /** 付款单号:FK-yyyyMMdd-三位流水 */
    private String nextPayNo() {
        String like = "FK-" + LocalDate.now().format(DAY) + "-";
        Long count = paymentMapper.selectCount(new LambdaQueryWrapper<Payment>()
                .likeRight(Payment::getPayNo, like));
        return like + String.format("%03d", count + 1);
    }
}
