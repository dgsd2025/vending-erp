package top.aole.vend.modules.settle.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.settle.domain.entity.Deduction;
import top.aole.vend.modules.settle.domain.entity.Payment;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.mapper.DeductionMapper;
import top.aole.vend.modules.settle.mapper.PaymentMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 应付结算单服务(§9.2 样板流程中枢):
 *
 * - 自动生成(SettleBillGenerator 事件入口):采购入库确认 → 待确认,应结=实收入库金额;
 * - 老板复核确认(角色头,与解锁同一把尺子):勾选带入**同供应商**待抵扣(P2-11 防串户,
 *   穿行场景13)+ 自动冲抵在挂红字 → 实结 = 应结 − 抵扣 − 红字 → 待付款,采购单进"待结算";
 * - 红冲连锁(§13.2-1,RedFlushService 调 onPurchaseRedFlush):
 *   未付款 → 结算单作废 + 抵扣释放回待抵扣;已付款 → 不动已完成付款,
 *   生成红字结算单(金额=已付款额)待冲抵下一单。
 */
@Service
@RequiredArgsConstructor
public class SettleBillService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final SettleBillMapper billMapper;
    private final DeductionMapper deductionMapper;
    private final PaymentMapper paymentMapper;
    private final DocService docService;
    private final PeriodLockService periodLockService;
    private final OpLogService opLogService;

    // ============================== 生成(唯一生产者=采购入库确认事件) ==============================

    /** 仅供 SettleBillGenerator(同事务事件)调用 */
    @Transactional(rollbackFor = Exception.class)
    public SettleBill createFromPurchase(DocHead purchaseDoc, BigDecimal amountDue, LocalDate dueDate) {
        SettleBill bill = new SettleBill();
        bill.setBillNo(nextBillNo(purchaseDoc.getBizDate()));
        bill.setBillType("应付");
        bill.setDirection(SettleBill.DIR_NORMAL);
        bill.setSupplierId(purchaseDoc.getSupplierId());
        bill.setSourceDocId(purchaseDoc.getId());
        bill.setAmountDue(amountDue);
        bill.setDeductionAmount(BigDecimal.ZERO);
        bill.setAmountActual(amountDue);
        bill.setBillStatus(SettleBill.ST_PENDING_CONFIRM);
        bill.setDueDate(dueDate);
        bill.setBookPeriod(purchaseDoc.getBookPeriod());
        bill.setCreateUser(purchaseDoc.getConfirmBy());
        billMapper.insert(bill);
        return bill;
    }

    // ============================== 老板复核确认(待确认 → 待付款) ==============================

    /**
     * 老板复核确认结算单:勾选带入同供应商待抵扣 + 自动冲抵在挂红字。
     * 实结 = 应结 − Σ抵扣 − Σ红字;采购单 已确认 → 待结算(应付环节正式开始)。
     *
     * @param role X-User-Role 占位角色头,必须=老板(与解锁/锁账红冲同一把尺子)
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfirmResult confirm(Long billId, List<Long> deductionIds, Long userId, String operator, String role) {
        periodLockService.assertBossRole(role, "结算单复核确认(§9.2 确认点②)");
        SettleBill bill = mustGet(billId);
        if (!SettleBill.DIR_NORMAL.equals(bill.getDirection())) {
            throw new BizException("红字结算单不走复核确认,只能被下一张正常单冲抵");
        }
        if (!SettleBill.ST_PENDING_CONFIRM.equals(bill.getBillStatus())) {
            throw new BizException(String.format("结算单[%s]状态为[%s],仅[待确认]可复核", bill.getBillNo(), bill.getBillStatus()));
        }

        // ---- 带入待抵扣(P2-11:只允许同供应商,防串户) ----
        BigDecimal dedTotal = BigDecimal.ZERO;
        List<Deduction> selected = new ArrayList<>();
        for (Long dedId : deductionIds == null ? Collections.<Long>emptyList() : deductionIds) {
            Deduction ded = deductionMapper.selectById(dedId);
            if (ded == null) {
                throw new BizException("抵扣确认单不存在:id=" + dedId);
            }
            if (!Deduction.ST_PENDING.equals(ded.getDedStatus())) {
                throw new BizException(String.format("抵扣确认单[%s]状态为[%s],仅[待抵扣]可带入", ded.getDedNo(), ded.getDedStatus()));
            }
            if (!bill.getSupplierId().equals(ded.getSupplierId())) {
                throw new BizException(String.format(
                        "抵扣确认单[%s]属于供应商[%d],与结算单供应商[%d]不符:结算单只允许带入同供应商待抵扣(P2-11 防串户)",
                        ded.getDedNo(), ded.getSupplierId(), bill.getSupplierId()));
            }
            dedTotal = dedTotal.add(ded.getAmount());
            selected.add(ded);
        }
        if (dedTotal.compareTo(bill.getAmountDue()) > 0) {
            throw new BizException(String.format("带入抵扣合计 %s 超过应结金额 %s:请留部分待抵扣用于下一单",
                    dedTotal, bill.getAmountDue()));
        }

        // ---- 自动冲抵在挂红字(§13.2-1 冲下一单;先进先出,吃不下的留给再下一单) ----
        // M3-9 P1-8:红字占用改条件更新(WHERE 待冲抵)——并发确认两张单时同一红字只许被一张吃掉
        BigDecimal remaining = bill.getAmountDue().subtract(dedTotal);
        BigDecimal redTotal = BigDecimal.ZERO;
        List<SettleBill> appliedReds = new ArrayList<>();
        for (SettleBill red : billMapper.selectList(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSupplierId, bill.getSupplierId())
                .eq(SettleBill::getDirection, SettleBill.DIR_RED)
                .eq(SettleBill::getBillStatus, SettleBill.ST_RED_PENDING)
                .orderByAsc(SettleBill::getId))) {
            if (red.getAmountDue().compareTo(remaining) > 0) {
                continue; // 本单吃不下,整张留给下一单(不做拆分)
            }
            int occupied = billMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SettleBill>()
                    .eq(SettleBill::getId, red.getId())
                    .eq(SettleBill::getBillStatus, SettleBill.ST_RED_PENDING)
                    .set(SettleBill::getBillStatus, SettleBill.ST_RED_USED)
                    .set(SettleBill::getOffsetIntoBillId, bill.getId())
                    .set(SettleBill::getUpdateUser, userId));
            if (occupied != 1) {
                continue; // 已被并发的另一张单冲走,跳过
            }
            remaining = remaining.subtract(red.getAmountDue());
            redTotal = redTotal.add(red.getAmountDue());
            appliedReds.add(red);
        }

        // ---- 落账 ----
        // M3-9 P1-8:抵扣占用改条件更新(WHERE 待抵扣)——同一张待抵扣被两张结算单并发带入时,
        // 只许一张占到;占不到 = 已被别人用走,整单回滚(供应商不能少收两次钱)
        for (Deduction ded : selected) {
            int occupied = deductionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Deduction>()
                    .eq(Deduction::getId, ded.getId())
                    .eq(Deduction::getDedStatus, Deduction.ST_PENDING)
                    .set(Deduction::getDedStatus, Deduction.ST_USED)
                    .set(Deduction::getUsedSettleBillId, bill.getId())
                    .set(Deduction::getUpdateUser, userId));
            if (occupied != 1) {
                throw new BizException(String.format(
                        "抵扣确认单[%s]已被他人带入其他结算单(并发防同一张抵扣双用),请刷新后重新勾选", ded.getDedNo()));
            }
        }
        // M3-9 P1-8:结算单本身 待确认→待付款 也走条件更新抢占,防同一张单并发双复核
        int billClaimed = billMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SettleBill>()
                .eq(SettleBill::getId, bill.getId())
                .eq(SettleBill::getBillStatus, SettleBill.ST_PENDING_CONFIRM)
                .set(SettleBill::getDeductionAmount, dedTotal)
                .set(SettleBill::getAmountActual, remaining)
                .set(SettleBill::getBillStatus, SettleBill.ST_PENDING_PAY)
                .set(SettleBill::getUpdateUser, userId));
        if (billClaimed != 1) {
            throw new BizException(String.format("结算单[%s]已被他人复核(并发确认防双过账),请刷新查看", bill.getBillNo()));
        }
        bill.setDeductionAmount(dedTotal);
        bill.setAmountActual(remaining);
        bill.setBillStatus(SettleBill.ST_PENDING_PAY);

        // 采购单进入应付环节:已确认 → 待结算(§9.2 状态机)
        if (bill.getSourceDocId() != null
                && docService.getDoc(bill.getSourceDocId()).getHead().getDocStatus() == DocStatus.CONFIRMED) {
            docService.markPendingSettle(bill.getSourceDocId(), userId);
        }
        opLogService.record(operator, "结算单复核确认", "settle_bill", bill.getId(), null,
                String.format("应结%s − 抵扣%s − 红字%s = 实结%s(带入抵扣%d张/红字%d张)",
                        bill.getAmountDue(), dedTotal, redTotal, remaining, selected.size(), appliedReds.size()));

        ConfirmResult result = new ConfirmResult();
        result.setBillId(bill.getId());
        result.setAmountDue(bill.getAmountDue());
        result.setDeductionAmount(dedTotal);
        result.setRedOffsetAmount(redTotal);
        result.setAmountActual(remaining);
        return result;
    }

    // ============================== 红冲连锁(RedFlushService 调用,§13.2-1) ==============================

    /**
     * 采购单红冲的应付侧连锁(同事务):
     * - 无结算单(供应商为空/M3 前老单)→ 不动;
     * - 未付款(待确认/待付款,且无已付付款)→ 结算单作废 + 已带入抵扣释放回待抵扣;
     * - 已付款(存在 pay_time 非空的核销付款)→ 不动已完成付款,抵扣释放,
     *   生成红字结算单(金额=Σ已付款额)待冲抵下一单。
     *
     * @return 连锁说明(拼进红冲 op_log/返回给前端)
     */
    @Transactional(rollbackFor = Exception.class)
    public String onPurchaseRedFlush(Long originDocId, Long redDocId, Long userId, String operator) {
        SettleBill bill = billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSourceDocId, originDocId)
                .eq(SettleBill::getDirection, SettleBill.DIR_NORMAL)
                .ne(SettleBill::getBillStatus, SettleBill.ST_VOID)
                .last("LIMIT 1"));
        if (bill == null) {
            return "应付链:无关联结算单,无连锁";
        }
        BigDecimal paidTotal = paidTotalOf(bill.getId());
        int released = releaseDeductions(bill.getId(), userId);

        if (paidTotal.compareTo(BigDecimal.ZERO) <= 0) {
            // 未付款:结算单作废,链条止于此
            bill.setBillStatus(SettleBill.ST_VOID);
            bill.setDiffNote("原采购单红冲(红冲单据ID=" + redDocId + "),结算单作废,已带入抵扣释放");
            bill.setUpdateUser(userId);
            billMapper.updateById(bill);
            opLogService.record(operator, "红冲连锁-结算单作废", "settle_bill", bill.getId(), null,
                    "释放抵扣 " + released + " 张");
            return String.format("应付链:结算单[%s]未付款 → 已作废,释放待抵扣 %d 张", bill.getBillNo(), released);
        }

        // 已付款:不动已完成付款,生成红字冲抵下一单
        SettleBill red = new SettleBill();
        red.setBillNo(nextBillNo(LocalDate.now()));
        red.setBillType("应付");
        red.setDirection(SettleBill.DIR_RED);
        red.setSupplierId(bill.getSupplierId());
        red.setSourceDocId(redDocId);
        red.setAmountDue(paidTotal);
        red.setDeductionAmount(BigDecimal.ZERO);
        red.setAmountActual(paidTotal);
        red.setBillStatus(SettleBill.ST_RED_PENDING);
        red.setBookPeriod(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")));
        red.setCreateUser(userId);
        billMapper.insert(red);
        // M3-9 P1-6:原结算单同步关闭(置已作废终态)——货已退,剩余额(实结−已付)不许再挂"待付款/差异挂起"
        // 引导付款;已付部分由红字承接冲抵下一单。作废态叠加 P0-1 付款确认复核,双保险拒新付款。
        bill.setBillStatus(SettleBill.ST_VOID);
        bill.setDiffNote(String.format(
                "原采购单红冲(红冲单据ID=%d):已付 %s 由红字[%s]承接冲抵下一单,剩余额不再付款,本单关闭",
                redDocId, paidTotal, red.getBillNo()));
        bill.setUpdateUser(userId);
        billMapper.updateById(bill);
        opLogService.record(operator, "红冲连锁-生成应付红字", "settle_bill", red.getId(), null,
                String.format("原结算单[%s]已付 %s → 红字[%s]待冲抵下一单;原单关闭(已作废);释放抵扣 %d 张",
                        bill.getBillNo(), paidTotal, red.getBillNo(), released));
        return String.format("应付链:结算单[%s]已付款 %s → 生成红字[%s]冲抵下一单(不动已完成付款),原单关闭,释放待抵扣 %d 张",
                bill.getBillNo(), paidTotal, red.getBillNo(), released);
    }

    // ============================== 导入整批回滚的应付链守卫(M3-9 P0-3) ==============================

    /**
     * 导入整批回滚前的应付链 blocker 检查(只读不动账):
     * 来源单据挂着非作废正常结算单时——
     * - 已有付款(pay_time 非空)或状态不在[待确认] → blocker,指引走红冲连锁(会正确处理应付红字);
     * - 仅[待确认]且无付款 → 可随回滚同事务作废(走 {@link #voidUnpaidOnImportRollback})。
     */
    public List<String> importRollbackBlockers(List<Long> docIds) {
        List<String> blockers = new ArrayList<>();
        for (SettleBill bill : billsOfSourceDocs(docIds)) {
            BigDecimal paid = paidTotalOf(bill.getId());
            if (paid.compareTo(BigDecimal.ZERO) > 0) {
                blockers.add(String.format("结算单[%s]已付款 %s:不能整批回滚,请对该采购单走红冲连锁(会生成应付红字正确处理已付款)",
                        bill.getBillNo(), paid));
            } else if (!SettleBill.ST_PENDING_CONFIRM.equals(bill.getBillStatus())) {
                blockers.add(String.format("结算单[%s]状态为[%s](已进入应付流程):不能整批回滚,请对该采购单走红冲连锁",
                        bill.getBillNo(), bill.getBillStatus()));
            }
        }
        return blockers;
    }

    /**
     * 导入整批回滚的应付链连锁(与回滚同事务;须先过 {@link #importRollbackBlockers}):
     * 把来源单据挂着的[待确认且无付款]结算单作废 + 释放已带入抵扣(复用红冲连锁未付款分支逻辑)。
     *
     * @return 作废的结算单数
     */
    @Transactional(rollbackFor = Exception.class)
    public int voidUnpaidOnImportRollback(List<Long> docIds, String batchNo, Long userId, String operator) {
        int voided = 0;
        for (SettleBill bill : billsOfSourceDocs(docIds)) {
            if (!SettleBill.ST_PENDING_CONFIRM.equals(bill.getBillStatus())
                    || paidTotalOf(bill.getId()).compareTo(BigDecimal.ZERO) > 0) {
                throw new BizException(String.format(
                        "结算单[%s]状态[%s]/已有付款,不能随导入回滚作废(blocker 检查应已拦下,防御性拒绝)",
                        bill.getBillNo(), bill.getBillStatus()));
            }
            int released = releaseDeductions(bill.getId(), userId);
            bill.setBillStatus(SettleBill.ST_VOID);
            bill.setDiffNote("来源采购单随导入批次[" + batchNo + "]整批回滚,结算单作废,已带入抵扣释放");
            bill.setUpdateUser(userId);
            billMapper.updateById(bill);
            opLogService.record(operator, "导入回滚-结算单作废", "settle_bill", bill.getId(), null,
                    String.format("批次[%s];释放抵扣 %d 张", batchNo, released));
            voided++;
        }
        return voided;
    }

    /** 来源单据(docIds)挂着的全部非作废正常结算单 */
    private List<SettleBill> billsOfSourceDocs(List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Collections.emptyList();
        }
        return billMapper.selectList(new LambdaQueryWrapper<SettleBill>()
                .in(SettleBill::getSourceDocId, docIds)
                .eq(SettleBill::getDirection, SettleBill.DIR_NORMAL)
                .ne(SettleBill::getBillStatus, SettleBill.ST_VOID));
    }

    /** 红冲影响预览(RedFlushService.preview 取数):不落库,只描述会发生什么 */
    public String previewPurchaseRedFlushChain(Long originDocId) {
        SettleBill bill = billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSourceDocId, originDocId)
                .eq(SettleBill::getDirection, SettleBill.DIR_NORMAL)
                .ne(SettleBill::getBillStatus, SettleBill.ST_VOID)
                .last("LIMIT 1"));
        if (bill == null) {
            return "无关联结算单,红冲不触发应付连锁";
        }
        BigDecimal paidTotal = paidTotalOf(bill.getId());
        if (paidTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return String.format("结算单[%s]未付款:红冲将把它作废,并把已带入抵扣释放回待抵扣", bill.getBillNo());
        }
        return String.format("结算单[%s]已付款 %s:红冲不动已完成付款,将生成等额应付红字冲抵下一单(§13.2-1)",
                bill.getBillNo(), paidTotal);
    }

    // ============================== 查询/内部 ==============================

    public SettleBill mustGet(Long billId) {
        SettleBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new BizException("结算单不存在:id=" + billId);
        }
        return bill;
    }

    /** 某结算单已付合计(pay_time 非空=钱真动了;差异挂起也已付) */
    public BigDecimal paidTotalOf(Long billId) {
        return paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                        .eq(Payment::getSettleBillId, billId).isNotNull(Payment::getPayTime))
                .stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 把某结算单已带入的抵扣释放回待抵扣(红冲连锁) */
    private int releaseDeductions(Long billId, Long userId) {
        List<Deduction> used = deductionMapper.selectList(new LambdaQueryWrapper<Deduction>()
                .eq(Deduction::getUsedSettleBillId, billId).eq(Deduction::getDedStatus, Deduction.ST_USED));
        for (Deduction ded : used) {
            // set 显式置 NULL(entity-update 会忽略 null 字段,必须走 UpdateWrapper)
            deductionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Deduction>()
                    .eq(Deduction::getId, ded.getId())
                    .set(Deduction::getDedStatus, Deduction.ST_PENDING)
                    .set(Deduction::getUsedSettleBillId, null)
                    .set(Deduction::getUpdateUser, userId));
        }
        return used.size();
    }

    /** 结算单号:JS-yyyyMMdd-三位流水(正常/红字共号段,方向字段区分) */
    private String nextBillNo(LocalDate date) {
        String like = "JS-" + date.format(DAY) + "-";
        Long count = billMapper.selectCount(new LambdaQueryWrapper<SettleBill>()
                .likeRight(SettleBill::getBillNo, like));
        return like + String.format("%03d", count + 1);
    }

    /** 复核确认结果(前端弹窗展示 §9.2 算式) */
    @Data
    public static class ConfirmResult {
        private Long billId;
        private BigDecimal amountDue;
        private BigDecimal deductionAmount;
        private BigDecimal redOffsetAmount;
        private BigDecimal amountActual;
    }
}
