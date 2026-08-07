package top.aole.vend.modules.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.claim.domain.entity.Claim;
import top.aole.vend.modules.claim.mapper.ClaimMapper;
import top.aole.vend.modules.settle.domain.entity.Payment;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.PaymentMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;
import top.aole.vend.modules.settle.service.PayableService;
import top.aole.vend.modules.settlement.domain.entity.Settlement;
import top.aole.vend.modules.settlement.dto.SettlementDtos;
import top.aole.vend.modules.settlement.mapper.SettlementMapper;
import top.aole.vend.modules.settlement.service.SettlementService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 驾驶舱钱账红灯聚合(M3-9 七律修复 P1-2/P1-5,§9.3 明文「首页亮灯」):
 *
 * 四盏灯,数据口全部复用既有单一真相源,本类只聚合不另抄公式:
 * ① 逾期应付          → PayableService.payableAging(dueDate 过期,与供应商卡黄灯同尺);
 * ② 差异挂起聚合      → settle_bill + payment + settlement 三表「差异挂起」计数;
 * ③ 索赔超期未到账    → claim 申请中挂账 >30 天;
 * ④ 超期未结算(在途) → SettlementService.pendingAging(PLATFORM 最老待结算 >35 天)。
 */
@Service
@RequiredArgsConstructor
public class MoneyLightsService {

    /** 索赔申请中超期阈值(天):挂了一个月还没到账就该催 */
    public static final int CLAIM_AGING_THRESHOLD_DAYS = 30;

    private final PayableService payableService;
    private final SettlementService settlementService;
    private final SettleBillMapper settleBillMapper;
    private final PaymentMapper paymentMapper;
    private final SettlementMapper settlementMapper;
    private final ClaimMapper claimMapper;

    public MoneyLightsResp lights() {
        MoneyLightsResp resp = new MoneyLightsResp();

        // ① 逾期应付
        SettleDtos.PayableAgingResp payable = payableService.payableAging();
        resp.setOverduePayableCount(payable.getOverdueCount());
        resp.setMaxOverdueDays(payable.getMaxOverdueDays());

        // ② 差异挂起聚合(三表 UNION 口径:各自状态机的「差异挂起」)
        long billDiff = nz(settleBillMapper.selectCount(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getBillStatus, SettleBill.ST_DIFF)));
        long payDiff = nz(paymentMapper.selectCount(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPayStatus, Payment.ST_DIFF)));
        long stlDiff = nz(settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStlStatus, Settlement.ST_DIFF)));
        resp.setDiffBillCount(billDiff);
        resp.setDiffPaymentCount(payDiff);
        resp.setDiffSettlementCount(stlDiff);
        resp.setDiffTotal(billDiff + payDiff + stlDiff);

        // ③ 索赔超期未到账(申请中挂账 >30 天)
        LocalDateTime claimLine = LocalDateTime.now().minusDays(CLAIM_AGING_THRESHOLD_DAYS);
        resp.setClaimOverdueCount(nz(claimMapper.selectCount(new LambdaQueryWrapper<Claim>()
                .eq(Claim::getClaimStatus, Claim.STATUS_PENDING)
                .lt(Claim::getCreateTime, claimLine))));
        Claim oldest = claimMapper.selectList(new LambdaQueryWrapper<Claim>()
                        .eq(Claim::getClaimStatus, Claim.STATUS_PENDING)
                        .orderByAsc(Claim::getCreateTime).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (oldest != null && oldest.getCreateTime() != null) {
            resp.setClaimOldestDays((int) ChronoUnit.DAYS.between(
                    oldest.getCreateTime().toLocalDate(), java.time.LocalDate.now()));
        }
        resp.setClaimThresholdDays(CLAIM_AGING_THRESHOLD_DAYS);

        // ④ 超期未结算(在途货款账龄,PLATFORM 才有)
        SettlementDtos.PendingAgingResp aging = settlementService.pendingAging();
        resp.setMode(aging.getMode());
        resp.setSettleOverdue(aging.isOverdue());
        resp.setSettleOldestDays(aging.getOldestDays());
        resp.setSettlePendingBalance(aging.getPendingBalance());
        resp.setSettleThresholdDays(aging.getThresholdDays());

        resp.setRedTotal(resp.getOverduePayableCount()
                + (int) resp.getDiffTotal()
                + (int) resp.getClaimOverdueCount()
                + (resp.isSettleOverdue() ? 1 : 0));
        return resp;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    /** 驾驶舱钱账四灯(红灯区收编,§9.3「首页亮灯」) */
    @Data
    public static class MoneyLightsResp {
        // ① 逾期应付
        private int overduePayableCount;
        private int maxOverdueDays;
        // ② 差异挂起(三表)
        private long diffBillCount;
        private long diffPaymentCount;
        private long diffSettlementCount;
        private long diffTotal;
        // ③ 索赔超期
        private long claimOverdueCount;
        private Integer claimOldestDays;
        private int claimThresholdDays;
        // ④ 超期未结算(在途)
        private String mode;
        private boolean settleOverdue;
        private Integer settleOldestDays;
        private BigDecimal settlePendingBalance;
        private int settleThresholdDays;
        /** 四灯合计(驾驶舱 redCount 收编) */
        private int redTotal;
    }
}
