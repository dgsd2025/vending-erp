package top.aole.vend.modules.claim.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.claim.domain.entity.Claim;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.mapper.ClaimMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.mapper.StocktakeItemMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 索赔单生命周期(M3-4,§9.3 场景4 · 穿行场景6 修正 P0-6):
 *
 * 盘亏归因(吞货掉货/被盗)→ 发起申请(对象 厂家/平台,金额默认=盘亏成本额)→ 申请中
 *   →(赔付凭证必传)已到账:MoneyPostingEvent(索赔到账 → 其他收入-赔付)+ 回填 cash_flow_id
 *   → 放弃:备注必填,退出索赔应收。
 *
 * 两个口径接口(§13.1):
 * - {@link #receivable()}:Σ申请中金额 =「索赔应收」,资产快照净流动资产公式第 4 项(M3-6 直接调);
 * - {@link #netShrinkage(String, String)}:净损耗 = 损耗(已确认盘亏/报损单成本额)− 已获赔(损耗报表用)。
 *
 * 纪律:只发 MoneyPostingEvent 过账(cash_flow 唯一写手包私有,M3-1 基座契约);
 *       stocktake_item.claim_id 用条件 UPDATE 只回填该列(不碰 stocktake 服务,M3-5 并行票在改)。
 */
@Service
@RequiredArgsConstructor
public class ClaimService {

    /** 可索赔的盘亏归因(五步向导第 3 步"处理/索赔"出口) */
    private static final List<String> CLAIMABLE_REASONS = new ArrayList<>();

    static {
        CLAIMABLE_REASONS.add(StocktakeItem.REASON_SWALLOW);
        CLAIMABLE_REASONS.add(StocktakeItem.REASON_THEFT);
    }

    public static final String REF_DOC_TYPE = "索赔单";
    public static final String ATTACH_REF_TYPE = "claim";

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ClaimMapper claimMapper;
    private final StocktakeItemMapper stocktakeItemMapper;
    private final DocHeadMapper docHeadMapper;
    private final CashFlowMapper cashFlowMapper;
    private final AttachmentService attachmentService;
    private final ApplicationEventPublisher publisher;
    private final OpLogService opLogService;

    // ============================== 发起申请 ==============================

    @Transactional(rollbackFor = Exception.class)
    public Long create(ClaimDtos.CreateReq req, Long userId, String operator) {
        if (!Claim.VALID_TARGETS.contains(req.getClaimTarget())) {
            throw new BizException("索赔对象只能是 厂家/平台:" + req.getClaimTarget());
        }
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("索赔金额必须为正数:" + req.getAmount());
        }
        boolean fromStocktake = req.getSourceId() != null || !req.getStocktakeItemIds().isEmpty();

        Claim claim = new Claim();
        claim.setClaimNo(generateNo());
        claim.setSourceType(fromStocktake ? Claim.SOURCE_STOCKTAKE : Claim.SOURCE_OTHER);
        claim.setSourceId(req.getSourceId());
        claim.setClaimTarget(req.getClaimTarget());
        claim.setAmount(req.getAmount());
        claim.setClaimStatus(Claim.STATUS_PENDING);
        claim.setRemark(StrUtil.blankToDefault(req.getRemark(), null));
        claim.setCreateUser(userId);
        claimMapper.insert(claim);

        // 回填盘亏行 claim_id:只认 归因可索赔(吞货掉货/被盗)+ 真盘亏(diff<0)+ 未索赔过 的行
        for (Long itemId : req.getStocktakeItemIds()) {
            StocktakeItem item = stocktakeItemMapper.selectById(itemId);
            if (item == null) {
                throw new BizException("盘点差异行不存在:id=" + itemId);
            }
            if (req.getSourceId() != null && !req.getSourceId().equals(item.getStocktakeId())) {
                throw new BizException(String.format("差异行 %d 不属于盘点单 %d", itemId, req.getSourceId()));
            }
            if (item.getDiffQty() == null || item.getDiffQty().signum() >= 0) {
                throw new BizException(String.format("差异行 %d 不是盘亏行(差异 %s),不能索赔",
                        itemId, item.getDiffQty()));
            }
            if (!CLAIMABLE_REASONS.contains(item.getDiffReason())) {
                throw new BizException(String.format("差异行 %d 归因「%s」不可索赔(可索赔:%s)",
                        itemId, item.getDiffReason(), String.join("/", CLAIMABLE_REASONS)));
            }
            // 条件 UPDATE 只回填 claim_id(claim_id 已有值=重复索赔,拒绝;不整行覆盖,避免与并行票互踩)
            int updated = stocktakeItemMapper.update(null, new LambdaUpdateWrapper<StocktakeItem>()
                    .eq(StocktakeItem::getId, itemId)
                    .isNull(StocktakeItem::getClaimId)
                    .set(StocktakeItem::getClaimId, claim.getId()));
            if (updated != 1) {
                throw new BizException(String.format("差异行 %d 已发起过索赔,不能重复(1 行盘亏只能索赔一次)", itemId));
            }
        }
        opLogService.record(operator, "发起索赔", "claim", claim.getId(), null, JSONUtil.toJsonStr(claim));
        return claim.getId();
    }

    // ============================== 到账登记(凭证门禁) ==============================

    @Transactional(rollbackFor = Exception.class)
    public ClaimDtos.ClaimRow receive(Long id, ClaimDtos.ReceiveReq req, Long userId, String operator) {
        Claim claim = mustGet(id);
        if (!Claim.STATUS_PENDING.equals(claim.getClaimStatus())) {
            throw new BizException(String.format("索赔单[%s]状态为[%s],仅申请中可登记到账",
                    claim.getClaimNo(), claim.getClaimStatus()));
        }
        // 凭证硬门禁(§9.3 场景4:赔付凭证必传;M3-1 基座 countOf 取数口)
        if (attachmentService.countOf(ATTACH_REF_TYPE, id) == 0) {
            throw new BizException(String.format(
                    "索赔单[%s]未上传赔付凭证——先传厂家/平台赔付凭证(refType=claim)再登记到账", claim.getClaimNo()));
        }
        BigDecimal received = req.getReceivedAmount() == null ? claim.getAmount() : req.getReceivedAmount();
        if (received.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("实际到账金额必须为正数:" + received);
        }
        LocalDateTime receivedTime = req.getReceivedTime() == null ? LocalDateTime.now() : req.getReceivedTime();

        // 过账:唯一合法通道 = 单据确认事件(同步同事务,写手校验失败整体回滚)
        MoneyPostingEvent event = new MoneyPostingEvent(REF_DOC_TYPE, id, userId);
        event.inflow(req.getAccountId(), received, CashFlowCategory.CLAIM_INCOME, receivedTime,
                String.format("索赔到账 %s(对象:%s)", claim.getClaimNo(), claim.getClaimTarget()));
        publisher.publishEvent(event);

        // 回填 cash_flow_id(同事务内查回写手刚落的流水)
        CashFlow flow = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, id)
                .orderByDesc(CashFlow::getId).last("LIMIT 1"));
        if (flow == null) {
            throw new BizException("索赔到账过账后未查到流水(不应发生),整体回滚");
        }
        String before = JSONUtil.toJsonStr(claim);
        claim.setClaimStatus(Claim.STATUS_RECEIVED);
        claim.setReceivedAmount(received);
        claim.setReceivedTime(receivedTime);
        claim.setCashFlowId(flow.getId());
        claim.setUpdateUser(userId);
        claimMapper.updateById(claim);
        opLogService.record(operator, "索赔到账登记", "claim", id, before, JSONUtil.toJsonStr(claim));
        return toRow(claim);
    }

    // ============================== 放弃(备注必填) ==============================

    @Transactional(rollbackFor = Exception.class)
    public void abandon(Long id, ClaimDtos.AbandonReq req, Long userId, String operator) {
        Claim claim = mustGet(id);
        if (!Claim.STATUS_PENDING.equals(claim.getClaimStatus())) {
            throw new BizException(String.format("索赔单[%s]状态为[%s],仅申请中可放弃",
                    claim.getClaimNo(), claim.getClaimStatus()));
        }
        if (StrUtil.isBlank(req.getRemark())) {
            throw new BizException("放弃索赔必须填写原因备注(留痕)");
        }
        String before = JSONUtil.toJsonStr(claim);
        claim.setClaimStatus(Claim.STATUS_ABANDONED);
        claim.setRemark(req.getRemark());
        claim.setUpdateUser(userId);
        claimMapper.updateById(claim);
        opLogService.record(operator, "放弃索赔", "claim", id, before, JSONUtil.toJsonStr(claim));
    }

    // ============================== 口径接口(§13.1) ==============================

    /** 「索赔应收」= Σ申请中金额:资产快照净流动资产公式第 4 项(M3-6 直接调本方法) */
    public BigDecimal receivable() {
        List<Claim> pending = claimMapper.selectList(new LambdaQueryWrapper<Claim>()
                .eq(Claim::getClaimStatus, Claim.STATUS_PENDING));
        return pending.stream().map(Claim::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 净损耗 = 损耗 − 已获赔(§13.1,损耗报表口)。
     * 损耗侧 = 已确认 盘亏出库/报损 单成本额合计(数据生产者:盘点确认/报损,book_period 区间筛选);
     * 已获赔 = 索赔已到账金额合计(到账月区间筛选)。区间可空=全期。
     */
    public ClaimDtos.NetShrinkageResp netShrinkage(String fromPeriod, String toPeriod) {
        List<DocHead> lossDocs = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .in(DocHead::getDocType, DocType.LOSS_OUT, DocType.DAMAGE)
                .eq(DocHead::getDocStatus, DocStatus.CONFIRMED)
                .ge(StrUtil.isNotBlank(fromPeriod), DocHead::getBookPeriod, fromPeriod)
                .le(StrUtil.isNotBlank(toPeriod), DocHead::getBookPeriod, toPeriod));
        BigDecimal loss = lossDocs.stream()
                .map(DocHead::getTotalAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Claim> receivedClaims = claimMapper.selectList(new LambdaQueryWrapper<Claim>()
                .eq(Claim::getClaimStatus, Claim.STATUS_RECEIVED));
        BigDecimal compensated = receivedClaims.stream()
                .filter(c -> inPeriod(c.getReceivedTime(), fromPeriod, toPeriod))
                .map(Claim::getReceivedAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ClaimDtos.NetShrinkageResp resp = new ClaimDtos.NetShrinkageResp();
        resp.setLossAmount(loss);
        resp.setCompensatedAmount(compensated);
        resp.setNetAmount(loss.subtract(compensated));
        resp.setFromPeriod(fromPeriod);
        resp.setToPeriod(toPeriod);
        return resp;
    }

    // ============================== 查询 ==============================

    public List<ClaimDtos.ClaimRow> list(String status) {
        List<ClaimDtos.ClaimRow> rows = new ArrayList<>();
        for (Claim c : claimMapper.selectList(new LambdaQueryWrapper<Claim>()
                .eq(StrUtil.isNotBlank(status), Claim::getClaimStatus, status)
                .orderByDesc(Claim::getId))) {
            rows.add(toRow(c));
        }
        return rows;
    }

    public ClaimDtos.ClaimRow detail(Long id) {
        return toRow(mustGet(id));
    }

    // ============================== 内部 ==============================

    private boolean inPeriod(LocalDateTime time, String fromPeriod, String toPeriod) {
        if (time == null) {
            return false;
        }
        String period = time.format(PERIOD);
        if (StrUtil.isNotBlank(fromPeriod) && period.compareTo(fromPeriod) < 0) {
            return false;
        }
        return !StrUtil.isNotBlank(toPeriod) || period.compareTo(toPeriod) <= 0;
    }

    private ClaimDtos.ClaimRow toRow(Claim c) {
        ClaimDtos.ClaimRow row = new ClaimDtos.ClaimRow();
        row.setId(c.getId());
        row.setClaimNo(c.getClaimNo());
        row.setSourceType(c.getSourceType());
        row.setSourceId(c.getSourceId());
        row.setClaimTarget(c.getClaimTarget());
        row.setAmount(c.getAmount());
        row.setClaimStatus(c.getClaimStatus());
        row.setReceivedAmount(c.getReceivedAmount());
        row.setReceivedTime(c.getReceivedTime());
        row.setCashFlowId(c.getCashFlowId());
        row.setRemark(c.getRemark());
        row.setCreateTime(c.getCreateTime());
        row.setAttachmentCount(attachmentService.countOf(ATTACH_REF_TYPE, c.getId()));
        return row;
    }

    private Claim mustGet(Long id) {
        Claim claim = claimMapper.selectById(id);
        if (claim == null) {
            throw new BizException("索赔单不存在:id=" + id);
        }
        return claim;
    }

    private String generateNo() {
        String like = "SP-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Long count = claimMapper.selectCount(new LambdaQueryWrapper<Claim>()
                .likeRight(Claim::getClaimNo, like));
        return like + String.format("%03d", count + 1);
    }
}
