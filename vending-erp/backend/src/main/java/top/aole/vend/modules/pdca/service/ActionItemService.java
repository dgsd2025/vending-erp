package top.aole.vend.modules.pdca.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.IPdcaDraftService;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.pdca.domain.entity.ActionItem;
import top.aole.vend.modules.pdca.dto.PdcaDtos;
import top.aole.vend.modules.pdca.mapper.ActionItemMapper;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.mapper.StocktakeItemMapper;
import top.aole.vend.modules.stocktake.mapper.StocktakeMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 改进任务(§8.4 action_item 激活):CRUD + 到期自动回查三分支 + 盘亏向导第 5 步一键起草。
 *
 * 回查三分支(用户拍板口径):
 * ① 指标达标 → 验证通过(改进见效,任务关闭);
 * ② 指标未达 → 未见效升级(提示老板换招:升级处理/重新登记);
 * ③ 指标取不到(无键/键未注册/本月没盘点等) → 需人工判定,状态不动,留 verify_note 说明原因。
 */
@Service
@RequiredArgsConstructor
public class ActionItemService {

    /** 来源引用类型:盘点单(五步向导起草) */
    public static final String REF_STOCKTAKE = "盘点单";

    /** 盘亏起草覆盖的"有改进空间"原因(账错类/原因不明不起草:前者补账,后者下轮重点盘) */
    public static final List<String> IMPROVABLE_REASONS = Arrays.asList("吞货掉货", "过期报损", "被盗");

    /**
     * 起草措施建议(规则版预填;M4-5 AI 票接真 LLM 网关后由 LLM 润色起草,
     * 落库时 ai_draft=1 + llm_call_id,本处即 mock 位:键名/入参不变,只换文案生成器)。
     */
    private static final Map<String, String> MEASURE_SUGGEST = new LinkedHashMap<String, String>() {{
        put("吞货掉货", "报修该货道弹簧/联系厂家检修;吞货可向厂家索赔(第 4 步)");
        put("过期报损", "下调该品机内上限/缩短保质期预警阈值;临期品优先促销");
        put("被盗", "查监控定位时段;换锁/加固取货口");
    }};

    private final ActionItemMapper itemMapper;
    private final PdcaMetricService metricService;
    private final OpLogService opLogService;
    private final StocktakeMapper stocktakeMapper;
    private final StocktakeItemMapper stocktakeItemMapper;
    /** 接入点#7:AI 起草改进措施(mock 网关,真 key 自动升级);M4-8 P2-4 兑现"注入即用" */
    private final IPdcaDraftService pdcaDraftService;

    // ============================== CRUD ==============================

    @Transactional(rollbackFor = Exception.class)
    public Long create(PdcaDtos.ItemSaveReq req, Long userId, String operator) {
        assertScene(req.getSourceScene());
        ActionItem item = new ActionItem();
        apply(item, req);
        item.setItemStatus(ActionItem.ST_OPEN);
        item.setAiDraft(Boolean.TRUE.equals(req.getAiDraft()));
        item.setLlmCallId(req.getLlmCallId());
        item.setCreateUser(userId);
        // 登记即取一次基线值:改进前的起点,回查报告"从多少改到多少"才有对照
        PdcaMetricService.Resolved base = metricService.resolve(item.getMetricKey(), item.getMetricParam());
        item.setBaselineValue(base.value);
        itemMapper.insert(item);
        opLogService.record(operator, "登记改进任务", "action_item", item.getId(), null, item);
        return item.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PdcaDtos.ItemSaveReq req, Long userId, String operator) {
        ActionItem item = mustGet(id);
        if (!ActionItem.ST_OPEN.equals(item.getItemStatus())
                && !ActionItem.ST_ESCALATED.equals(item.getItemStatus())) {
            throw new BizException("任务已" + item.getItemStatus() + ",不可再编辑(闭环记录不可篡改)");
        }
        assertScene(req.getSourceScene());
        ActionItem before = itemMapper.selectById(id);
        apply(item, req);
        // 换了指标三件套 → 基线重取(旧基线对新指标无意义)
        if (!StrUtil.equals(before.getMetricKey(), item.getMetricKey())
                || !StrUtil.equals(before.getMetricParam(), item.getMetricParam())) {
            item.setBaselineValue(metricService.resolve(item.getMetricKey(), item.getMetricParam()).value);
        }
        item.setUpdateUser(userId);
        itemMapper.updateById(item);
        opLogService.record(operator, "修改改进任务", "action_item", id, before, item);
    }

    /** 手动关闭(升级后另立新任务/问题自然消失等场景;留痕必填原因) */
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id, String note, Long userId, String operator) {
        ActionItem item = mustGet(id);
        if (ActionItem.ST_PASSED.equals(item.getItemStatus()) || ActionItem.ST_CLOSED.equals(item.getItemStatus())) {
            throw new BizException("任务已是终态:" + item.getItemStatus());
        }
        if (StrUtil.isBlank(note)) {
            throw new BizException("手动关闭必须写原因(闭环留痕)");
        }
        String before = item.getItemStatus();
        item.setItemStatus(ActionItem.ST_CLOSED);
        item.setVerifyNote("手动关闭:" + note);
        item.setUpdateUser(userId);
        itemMapper.updateById(item);
        opLogService.record(operator, "关闭改进任务", "action_item", id, before, ActionItem.ST_CLOSED + ":" + note);
    }

    public List<PdcaDtos.ItemRow> list(String status, String scene, String refType, Long refId) {
        LambdaQueryWrapper<ActionItem> qw = new LambdaQueryWrapper<ActionItem>()
                .eq(StrUtil.isNotBlank(status), ActionItem::getItemStatus, status)
                .eq(StrUtil.isNotBlank(scene), ActionItem::getSourceScene, scene)
                .eq(StrUtil.isNotBlank(refType), ActionItem::getSourceRefType, refType)
                .eq(refId != null, ActionItem::getSourceRefId, refId)
                .orderByAsc(ActionItem::getItemStatus)
                .orderByAsc(ActionItem::getVerifyDate)
                .orderByDesc(ActionItem::getId);
        List<PdcaDtos.ItemRow> rows = new ArrayList<>();
        for (ActionItem item : itemMapper.selectList(qw)) {
            rows.add(toRow(item));
        }
        // 到期的排最前(进行中+到期 = 今天必须处理)
        rows.sort((a, b) -> Boolean.compare(b.isDue(), a.isDue()));
        return rows;
    }

    /** 驾驶舱卡:本周(今天起 7 天内)到期待验证 + 已到期未回查(进行中/未见效) */
    public List<PdcaDtos.ItemRow> dueThisWeek() {
        LocalDate line = LocalDate.now().plusDays(7);
        List<PdcaDtos.ItemRow> rows = new ArrayList<>();
        for (ActionItem item : itemMapper.selectList(new LambdaQueryWrapper<ActionItem>()
                .in(ActionItem::getItemStatus, ActionItem.ST_OPEN, ActionItem.ST_ESCALATED)
                .le(ActionItem::getVerifyDate, line)
                .orderByAsc(ActionItem::getVerifyDate))) {
            rows.add(toRow(item));
        }
        return rows;
    }

    // ============================== 到期自动回查 ==============================

    /**
     * 单条回查:取指标当前值 vs 目标 → 三分支。
     * 未见效升级态允许再回查(老板加招后隔月复验),终态(通过/关闭)不许。
     */
    @Transactional(rollbackFor = Exception.class)
    public PdcaDtos.RecheckResp recheck(Long id, String operator) {
        ActionItem item = mustGet(id);
        if (ActionItem.ST_PASSED.equals(item.getItemStatus()) || ActionItem.ST_CLOSED.equals(item.getItemStatus())) {
            throw new BizException("任务已是终态(" + item.getItemStatus() + "),无需回查");
        }
        String before = item.getItemStatus();
        PdcaMetricService.Resolved r = metricService.resolve(item.getMetricKey(), item.getMetricParam());

        PdcaDtos.RecheckResp resp = new PdcaDtos.RecheckResp();
        resp.setItemId(id);
        resp.setTargetValue(item.getTargetValue());
        resp.setCompareOp(item.getCompareOp());
        item.setVerifiedAt(LocalDateTime.now());

        if (r.value == null || item.getTargetValue() == null) {
            // 分支③:取不到数/没定目标 → 需人工判定,状态不动
            resp.setResult(ActionItem.VR_MANUAL);
            resp.setNote(r.value == null ? r.note : "未设置目标值,需人工判定");
            item.setVerifyResult(ActionItem.VR_MANUAL);
            item.setVerifyNote(resp.getNote());
        } else {
            item.setVerifyValue(r.value);
            boolean pass = ActionItem.OP_GE.equals(item.getCompareOp())
                    ? r.value.compareTo(item.getTargetValue()) >= 0
                    : r.value.compareTo(item.getTargetValue()) <= 0;
            String cmp = "当前 " + trim(r.value) + " " + (pass ? "达标" : "未达") + " 目标 "
                    + item.getCompareOp() + " " + trim(item.getTargetValue())
                    + (StrUtil.isBlank(r.note) ? "" : "(" + r.note + ")");
            if (pass) {
                // 分支①:达标 → 验证通过(关闭)
                resp.setResult(ActionItem.VR_PASS);
                item.setItemStatus(ActionItem.ST_PASSED);
            } else {
                // 分支②:未达 → 未见效升级
                resp.setResult(ActionItem.VR_FAIL);
                item.setItemStatus(ActionItem.ST_ESCALATED);
                cmp += " → 改进没见效,该升级处理(换措施另立任务,或手动关闭说明原因)";
            }
            resp.setCurrentValue(r.value);
            resp.setNote(cmp);
            item.setVerifyResult(resp.getResult());
            item.setVerifyNote(cmp);
        }
        itemMapper.updateById(item);
        resp.setItemStatus(item.getItemStatus());
        opLogService.record(operator, "回查改进任务", "action_item", id,
                before, resp.getResult() + ":" + resp.getNote());
        return resp;
    }

    /** 批量到期回查:验证日到了的进行中任务,自动逐条判定(驾驶舱/看板"一键回查"入口) */
    @Transactional(rollbackFor = Exception.class)
    public PdcaDtos.RecheckBatchResp recheckDue(String operator) {
        List<ActionItem> due = itemMapper.selectList(new LambdaQueryWrapper<ActionItem>()
                .eq(ActionItem::getItemStatus, ActionItem.ST_OPEN)
                .le(ActionItem::getVerifyDate, LocalDate.now())
                .orderByAsc(ActionItem::getVerifyDate));
        PdcaDtos.RecheckBatchResp resp = new PdcaDtos.RecheckBatchResp();
        resp.setTotal(due.size());
        for (ActionItem item : due) {
            PdcaDtos.RecheckResp one = recheck(item.getId(), operator);
            resp.getDetails().add(one);
            if (ActionItem.VR_PASS.equals(one.getResult())) {
                resp.setPassed(resp.getPassed() + 1);
            } else if (ActionItem.VR_FAIL.equals(one.getResult())) {
                resp.setFailed(resp.getFailed() + 1);
            } else {
                resp.setManual(resp.getManual() + 1);
            }
        }
        return resp;
    }

    // ============================== 盘亏向导第 5 步:一键起草 ==============================

    /**
     * 从已完成盘点单起草改进任务(预填不落库,老板在弹窗里改完确认才 create):
     * 按"有改进空间"的原因(吞货/过期/被盗)逐原因一张草稿,
     * 验证指标 = 该原因当月损耗额(stocktake.loss_amount + 原因参数),
     * 目标 = 本次损耗额减半(至少降到 ¥10 线),验证日 = 下次月盘(下月 1 日)。
     */
    public PdcaDtos.DraftResp draftFromStocktake(Long stocktakeId) {
        Stocktake st = stocktakeMapper.selectById(stocktakeId);
        if (st == null) {
            throw new BizException("盘点单不存在:" + stocktakeId);
        }
        if (!Stocktake.ST_DONE.equals(st.getStStatus())) {
            throw new BizException("盘点单未完成(当前:" + st.getStStatus() + "),先走完五步向导第 3 步确认过账");
        }
        PdcaDtos.DraftResp resp = new PdcaDtos.DraftResp();
        resp.setStocktakeId(stocktakeId);
        resp.setStNo(st.getStNo());
        resp.setExisting(list(null, null, REF_STOCKTAKE, stocktakeId));

        // 逐原因汇总盘亏(件数/成本额),只挑"有改进空间"的原因
        Map<String, BigDecimal> amountByReason = new LinkedHashMap<>();
        Map<String, Integer> countByReason = new LinkedHashMap<>();
        for (StocktakeItem item : stocktakeItemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                .eq(StocktakeItem::getStocktakeId, stocktakeId))) {
            if (item.getDiffQty() == null || item.getDiffQty().signum() >= 0
                    || Boolean.TRUE.equals(item.getOfflineExempt())) {
                continue;
            }
            String reason = StrUtil.blankToDefault(item.getDiffReason(), "原因不明");
            if (!IMPROVABLE_REASONS.contains(reason)) {
                continue;
            }
            BigDecimal amount = item.getDiffAmount() == null ? BigDecimal.ZERO : item.getDiffAmount().abs();
            amountByReason.merge(reason, amount, BigDecimal::add);
            countByReason.merge(reason, 1, Integer::sum);
        }

        LocalDate nextMonthly = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        String month = st.getSnapshotTime() == null ? metricService.currentPeriod()
                : st.getSnapshotTime().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        for (Map.Entry<String, BigDecimal> e : amountByReason.entrySet()) {
            String reason = e.getKey();
            BigDecimal amount = e.getValue().setScale(2, RoundingMode.HALF_UP);
            PdcaDtos.ItemSaveReq draft = new PdcaDtos.ItemSaveReq();
            draft.setSourceScene(ActionItem.SCENE_STOCKTAKE);
            draft.setProblemDesc(month + " 盘点(" + st.getStNo() + ")「" + reason + "」损耗 "
                    + countByReason.get(reason) + " 行 · ¥" + amount);
            draft.setMetricKey(PdcaMetricService.K_LOSS_AMOUNT);
            draft.setMetricParam(reason);
            // 目标:损耗额减半,最低压到 ¥10 线(mockup "过期报损<¥10/月" 同款)
            BigDecimal target = amount.divide(new BigDecimal("2"), 0, RoundingMode.HALF_UP)
                    .max(BigDecimal.TEN);
            draft.setTargetValue(target);
            draft.setCompareOp(ActionItem.OP_LE);
            draft.setVerifyMetric("「" + reason + "」当月损耗额 ≤ ¥" + target + "(下次月盘回查)");
            draft.setVerifyDate(nextMonthly);
            draft.setSourceRefType(REF_STOCKTAKE);
            draft.setSourceRefId(stocktakeId);
            // M4-8 P2-4:措施改由 #7 AI 起草服务出(mock 网关落四件套+idempotent);
            // 网关异常时退回硬编码 MEASURE_SUGGEST(业务不断),诚实不号称 AI。
            applyMeasure(draft, reason, amount, target, st, month, stocktakeId);
            resp.getDrafts().add(draft);
        }
        return resp;
    }

    /**
     * 填措施:优先走 #7 AI 起草服务(mock 网关,真 key 自动升级),拿到即标 ai_draft=1 + llm_call_id;
     * 网关异常 → 退回硬编码 MEASURE_SUGGEST(fallback),ai_draft 保持空,不冒认 AI。
     */
    private void applyMeasure(PdcaDtos.ItemSaveReq draft, String reason, BigDecimal amount,
                              BigDecimal target, Stocktake st, String month, Long stocktakeId) {
        try {
            IPdcaDraftService.PdcaDraftResult ai = pdcaDraftService.draft(
                    IPdcaDraftService.PdcaDraftReq.builder()
                            .bizKey("stocktake:" + stocktakeId + ":" + reason)
                            .metricName("「" + reason + "」当月损耗额")
                            .actualValue(amount)
                            .targetValue(target)
                            .scope(st.getStNo() + " · " + month)
                            .context("盘亏原因=" + reason + ",目标=损耗额减半压到 ¥" + target + " 以下")
                            .build());
            draft.setMeasure(ai.getMeasureDetail());
            draft.setAiDraft(Boolean.TRUE);
            draft.setLlmCallId(ai.getLlmCallId());
        } catch (Exception ex) {
            // fallback:AI 网关不可用,退回规则版预填,诚实标 ai_draft=false
            draft.setMeasure(MEASURE_SUGGEST.getOrDefault(reason, "现场排查后补写措施"));
            draft.setAiDraft(Boolean.FALSE);
        }
    }

    // ============================== 内部 ==============================

    private void apply(ActionItem item, PdcaDtos.ItemSaveReq req) {
        item.setSourceScene(req.getSourceScene());
        item.setProblemDesc(req.getProblemDesc());
        item.setMeasure(req.getMeasure());
        item.setOwnerUser(req.getOwnerUser());
        item.setMetricKey(StrUtil.blankToDefault(req.getMetricKey(), null));
        item.setMetricParam(StrUtil.blankToDefault(req.getMetricParam(), null));
        item.setTargetValue(req.getTargetValue());
        item.setCompareOp(ActionItem.OP_GE.equals(req.getCompareOp()) ? ActionItem.OP_GE : ActionItem.OP_LE);
        item.setVerifyDate(req.getVerifyDate());
        item.setSourceRefType(req.getSourceRefType());
        item.setSourceRefId(req.getSourceRefId());
        // 人话指标:没填就按三件套自动生成一句
        if (StrUtil.isNotBlank(req.getVerifyMetric())) {
            item.setVerifyMetric(req.getVerifyMetric());
        } else if (item.getMetricKey() != null && item.getTargetValue() != null) {
            PdcaMetricService.MetricDef def = metricService.def(item.getMetricKey());
            String name = def == null ? item.getMetricKey() : def.name;
            String unit = def == null ? "" : def.unit;
            item.setVerifyMetric(name + (item.getMetricParam() == null ? "" : "(" + item.getMetricParam() + ")")
                    + " " + item.getCompareOp() + " " + trim(item.getTargetValue()) + unit);
        } else {
            item.setVerifyMetric("需人工判定(未设指标键/目标值)");
        }
    }

    private PdcaDtos.ItemRow toRow(ActionItem item) {
        PdcaDtos.ItemRow row = new PdcaDtos.ItemRow();
        row.setId(item.getId());
        row.setSourceScene(item.getSourceScene());
        row.setProblemDesc(item.getProblemDesc());
        row.setMeasure(item.getMeasure());
        row.setOwnerUser(item.getOwnerUser());
        row.setVerifyMetric(item.getVerifyMetric());
        row.setMetricKey(item.getMetricKey());
        PdcaMetricService.MetricDef def = metricService.def(item.getMetricKey());
        row.setMetricName(def == null ? null : def.name);
        row.setMetricParam(item.getMetricParam());
        row.setTargetValue(item.getTargetValue());
        row.setCompareOp(item.getCompareOp());
        row.setBaselineValue(item.getBaselineValue());
        row.setVerifyValue(item.getVerifyValue());
        row.setVerifyResult(item.getVerifyResult());
        row.setVerifyNote(item.getVerifyNote());
        row.setVerifiedAt(item.getVerifiedAt() == null ? null
                : item.getVerifiedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        row.setVerifyDate(item.getVerifyDate());
        row.setItemStatus(item.getItemStatus());
        row.setSourceRefType(item.getSourceRefType());
        row.setSourceRefId(item.getSourceRefId());
        row.setAiDraft(item.getAiDraft());
        row.setCreateTime(item.getCreateTime() == null ? null
                : item.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        boolean open = ActionItem.ST_OPEN.equals(item.getItemStatus())
                || ActionItem.ST_ESCALATED.equals(item.getItemStatus());
        LocalDate today = LocalDate.now();
        row.setDue(open && item.getVerifyDate() != null && !item.getVerifyDate().isAfter(today));
        row.setDueThisWeek(open && item.getVerifyDate() != null
                && !item.getVerifyDate().isAfter(today.plusDays(7)));
        return row;
    }

    private ActionItem mustGet(Long id) {
        ActionItem item = itemMapper.selectById(id);
        if (item == null) {
            throw new BizException("改进任务不存在:" + id);
        }
        return item;
    }

    private static void assertScene(String scene) {
        if (!Arrays.asList(ActionItem.SCENE_REPLENISH, ActionItem.SCENE_PURCHASE, ActionItem.SCENE_SELECT,
                ActionItem.SCENE_PRICE, ActionItem.SCENE_MONEY, ActionItem.SCENE_STOCKTAKE).contains(scene)) {
            throw new BizException("来源环节只能是 补货/采购/选品/定价/钱账/盘点:" + scene);
        }
    }

    private static String trim(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
