package top.aole.vend.modules.stocktake.money.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.service.PayableService;
import top.aole.vend.modules.settlement.mapper.SettlementQueryMapper;
import top.aole.vend.modules.stocktake.money.domain.entity.CashCheck;
import top.aole.vend.modules.stocktake.money.domain.entity.CashCheckItem;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.ActualRow;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.AdjustCreateReq;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.CheckDetailResp;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.CheckItemRow;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.CheckListRow;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.PayableExitResp;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.SaveActualsReq;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.SupplierPayableRow;
import top.aole.vend.modules.stocktake.money.mapper.CashCheckItemMapper;
import top.aole.vend.modules.stocktake.money.mapper.CashCheckMapper;
import top.aole.vend.modules.stocktake.money.mapper.CashMoneyQueryMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 月度钱盘三核对(M3-5,§8.2 月度 SOP 的 D2;结果落一张核对记录)。
 *
 * ① 账户核对:各真实账户 系统余额(期初+Σ流水)vs 实际余额(手填)
 *    → 差异行唯一出口 = 一键生成资金调整单(回链 adjust_doc_id,防重复生成);
 * ② 平台到账核对:结算模式 UNSET → 整块跳过 + 横幅原文留档(SettleModeService);
 *    PLATFORM/DIRECT → 「平台待结算」虚账余额留档核对(差异走结算单 M3-4,本页不开口);
 * ③ 应付核对:各供应商 系统应付(Σ正常实结−Σ红字−Σ已付款)vs 对方账(手填)
 *    → 不符出口两按钮:补录(跳付款/抵扣录入)或 红冲(跳对应单据)——只做跳转不重造。
 *
 * 完成守卫:账户/平台行必须全部填实际数;差异行必须走了出口或留了说明才许收口。
 */
@Service
@RequiredArgsConstructor
public class CashCheckService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 补录出口的前端跳转路由(采购页:入库/付款/抵扣录入所在地) */
    public static final String BACKFILL_ROUTE = "/purchase";

    private final CashCheckMapper checkMapper;
    private final CashCheckItemMapper itemMapper;
    private final CashMoneyQueryMapper queryMapper;
    private final AccountService accountService;
    private final SettleModeService settleModeService;
    private final CashAdjustService cashAdjustService;
    /** M3-9 P1-2:应付余额单一真相源(§7.3 公式唯一实现,与 p8 供应商卡同口径) */
    private final PayableService payableService;
    /** M3-9 P1-1:平台待结算真值口径(与结算单/总览同源,别处不许另抄) */
    private final SettlementQueryMapper settlementQueryMapper;
    private final OpLogService opLogService;

    // ============================== 开始核对(快照系统数) ==============================

    /** 开始本月钱盘:快照三类系统数;同时只允许一张进行中 */
    @Transactional(rollbackFor = Exception.class)
    public Long start(Long userId, String operator) {
        Long active = checkMapper.selectCount(new LambdaQueryWrapper<CashCheck>()
                .eq(CashCheck::getCheckStatus, CashCheck.ST_IN_PROGRESS));
        if (active != null && active > 0) {
            throw new BizException("已有进行中的钱盘核对,请先完成或作废再开新一轮(防两张单快照口径打架)");
        }
        String mode = settleModeService.currentMode();
        boolean unset = SettleModeService.MODE_UNSET.equals(mode);

        CashCheck check = new CashCheck();
        check.setCheckNo(generateCheckNo(LocalDate.now()));
        check.setCheckPeriod(YearMonth.now().format(PERIOD));
        check.setCheckStatus(CashCheck.ST_IN_PROGRESS);
        check.setSettleModeSnap(mode);
        check.setPlatformSkipped(unset);
        check.setPlatformNote(unset ? SettleModeService.UNSET_BANNER : null);
        check.setCreateUser(userId);
        checkMapper.insert(check);

        // ① 账户核对行:启用中的真实账户,系统数=期初+Σ流水(AccountService 公开口径)
        List<MoneyDtos.AccountRow> accounts = accountService.list();
        for (MoneyDtos.AccountRow a : accounts) {
            if (Boolean.TRUE.equals(a.getIsVirtual()) || a.getStatus() == null || a.getStatus() != 1) {
                continue;
            }
            insertItem(check.getId(), CashCheckItem.TYPE_ACCOUNT, a.getId(), a.getAccountName(),
                    a.getBalance(), null, userId);
        }
        // ② 平台到账核对行:模式已定型才核;UNSET 整块跳过。
        // M3-9 P1-1:PLATFORM 模式系统数取"平台待结算"真值(SettlementQueryMapper.pendingBalance,
        // 与结算单/总览同源口径)——虚拟账户的流水余额恒为期初(无任何写入方),不能给老板看。
        if (!unset) {
            boolean platform = SettleModeService.MODE_PLATFORM.equals(mode);
            BigDecimal pendingTrue = platform ? settlementQueryMapper.pendingBalance() : null;
            for (MoneyDtos.AccountRow a : accounts) {
                if (Boolean.TRUE.equals(a.getIsVirtual()) && "平台待结算".equals(a.getAccountType())
                        && a.getStatus() != null && a.getStatus() == 1) {
                    insertItem(check.getId(), CashCheckItem.TYPE_PLATFORM, a.getId(), a.getAccountName(),
                            platform ? pendingTrue : a.getBalance(), null, userId);
                }
            }
        }
        // ③ 应付核对行:系统数一律走 PayableService §7.3 公式(M3-9 P1-2 单一真相源,
        // 与 p8 供应商卡/对账单同一实现);只列有往来(期初/采购/退货/抵扣/付款任一非零)的供应商
        Map<Long, Long> lastDocMap = new HashMap<>();
        for (SupplierPayableRow s : queryMapper.lastSourceDocIds()) {
            lastDocMap.put(s.getSupplierId(), s.getLastSourceDocId());
        }
        for (SettleDtos.SupplierOverviewRow s : payableService.overview()) {
            boolean hasActivity = nzSig(s.getOpeningPayable()) || nzSig(s.getPurchaseTotal())
                    || nzSig(s.getReturnTotal()) || nzSig(s.getDeductionTotal()) || nzSig(s.getPaymentTotal());
            if (!hasActivity) {
                continue;
            }
            insertItem(check.getId(), CashCheckItem.TYPE_PAYABLE, s.getSupplierId(), s.getSupplierName(),
                    s.getBalance(), lastDocMap.get(s.getSupplierId()), userId);
        }
        opLogService.record(operator, "开始钱盘核对", "cash_check", check.getId(), null,
                JSONUtil.toJsonStr(check));
        return check.getId();
    }

    // ============================== 录实际数(手填) ==============================

    /** 录实际数:只传核对过的行;差异=实际−系统 落库 */
    @Transactional(rollbackFor = Exception.class)
    public void saveActuals(Long checkId, SaveActualsReq req, Long userId, String operator) {
        CashCheck check = mustGetInProgress(checkId);
        for (ActualRow row : req.getRows()) {
            CashCheckItem item = itemMapper.selectById(row.getItemId());
            if (item == null || !checkId.equals(item.getCheckId())) {
                throw new BizException("核对行不属于本核对记录:" + row.getItemId());
            }
            item.setActualAmount(row.getActualAmount());
            item.setDiffAmount(row.getActualAmount() == null ? null
                    : row.getActualAmount().subtract(item.getSystemAmount()));
            if (row.getNote() != null) {
                item.setNote(StrUtil.blankToDefault(row.getNote(), null));
            }
            item.setUpdateUser(userId);
            itemMapper.updateById(item);
        }
        opLogService.record(operator, "录钱盘实际数", "cash_check", check.getId(), null,
                "{\"rows\":" + req.getRows().size() + "}");
    }

    // ============================== 差异出口 ==============================

    /**
     * 账户差异行 → 一键生成资金调整单(唯一出口):
     * 盘盈(实际>系统)= 收;盘亏(实际<系统)= 支;金额=|差异|;回链防重复生成。
     * 返回调整单 docId(待老板确认,确认才落流水)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long genAdjust(Long checkId, Long itemId, Long userId, String operator) {
        CashCheck check = mustGetInProgress(checkId);
        CashCheckItem item = mustGetItem(checkId, itemId);
        if (!CashCheckItem.TYPE_ACCOUNT.equals(item.getItemType())) {
            if (CashCheckItem.TYPE_PLATFORM.equals(item.getItemType())) {
                throw new BizException("「平台待结算」是虚拟账户,不可手工收支:平台到账差异走平台结算单核销(M3-4)");
            }
            throw new BizException("应付差异不走资金调整单:出口是「补录」或「红冲」两按钮(P1-7)");
        }
        if (item.getDiffAmount() == null || item.getDiffAmount().compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("该账户行无差异(或未录实际数),不需要生成资金调整单");
        }
        if (item.getAdjustDocId() != null) {
            throw new BizException("该差异行已生成资金调整单(#" + item.getAdjustDocId()
                    + "),不许重复生成——改错请去红冲那张单");
        }
        AdjustCreateReq req = new AdjustCreateReq();
        req.setAccountId(item.getRefId());
        req.setAdjustAmount(item.getDiffAmount()); // 带符号:+盘盈收/−盘亏支
        req.setReason(item.getDiffAmount().signum() > 0 ? "盘盈" : "盘亏");
        req.setRemark(String.format("钱盘核对 %s:账户「%s」系统 %s vs 实际 %s,差异 %s",
                check.getCheckNo(), item.getRefName(),
                plain(item.getSystemAmount()), plain(item.getActualAmount()), plain(item.getDiffAmount())));
        req.setCashCheckId(checkId);
        Long docId = cashAdjustService.create(req, userId, operator);

        item.setAdjustDocId(docId);
        item.setUpdateUser(userId);
        itemMapper.updateById(item);
        opLogService.record(operator, "钱盘差异生成资金调整单", "cash_check", checkId,
                itemId, "doc#" + docId);
        return docId;
    }

    /**
     * 应付不符出口(两按钮,只做跳转留痕不重造):
     * 补录 → 前端跳采购页(付款/抵扣录入);红冲 → 前端打开对应单据(走既有红冲入口)。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayableExitResp markPayableExit(Long checkId, Long itemId, String action,
                                           Long userId, String operator) {
        mustGetInProgress(checkId);
        CashCheckItem item = mustGetItem(checkId, itemId);
        if (!CashCheckItem.TYPE_PAYABLE.equals(item.getItemType())) {
            throw new BizException("补录/红冲出口只适用于应付核对行;账户差异请走「生成资金调整单」");
        }
        if (item.getDiffAmount() == null || item.getDiffAmount().compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("该供应商应付无差异(或未录对方账),不需要走出口");
        }
        if (!CashCheckItem.EXIT_BACKFILL.equals(action) && !CashCheckItem.EXIT_RED_FLUSH.equals(action)) {
            throw new BizException("应付不符出口只有两个:补录/红冲(P1-7),收到:" + action);
        }
        item.setExitAction(action);
        item.setUpdateUser(userId);
        itemMapper.updateById(item);
        opLogService.record(operator, "应付核对不符出口", "cash_check", checkId,
                itemId, action);

        PayableExitResp resp = new PayableExitResp();
        resp.setAction(action);
        if (CashCheckItem.EXIT_BACKFILL.equals(action)) {
            resp.setRoute(BACKFILL_ROUTE);
            resp.setMessage("去采购页补录:漏记的入库/付款/抵扣补上后,应付余额自动修正");
        } else {
            resp.setSourceDocId(item.getSourceDocId());
            resp.setMessage(item.getSourceDocId() == null
                    ? "该供应商没有可红冲的来源单据(结算单还没生成过),请先核对是否漏导入库"
                    : "打开来源单据 #" + item.getSourceDocId() + ",走单据上的红冲入口(影响清单确认后连锁冲应付)");
        }
        return resp;
    }

    // ============================== 完成收口 ==============================

    /**
     * 完成钱盘:守卫 ①账户/平台行实际数必须全填;②差异行必须走了出口(调整单/补录/红冲)
     * 或留了说明;条件更新(WHERE 进行中)防双击。
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(Long checkId, Long userId, String operator) {
        CashCheck check = mustGetInProgress(checkId);
        List<CashCheckItem> items = itemsOf(checkId);
        for (CashCheckItem item : items) {
            boolean moneySide = !CashCheckItem.TYPE_PAYABLE.equals(item.getItemType());
            if (moneySide && item.getActualAmount() == null) {
                throw new BizException(String.format("「%s」还没录实际余额——钱盘三核对要求账户逐个数完才能收口",
                        item.getRefName()));
            }
            boolean hasDiff = item.getDiffAmount() != null
                    && item.getDiffAmount().compareTo(BigDecimal.ZERO) != 0;
            if (!hasDiff) {
                continue;
            }
            if (CashCheckItem.TYPE_ACCOUNT.equals(item.getItemType())
                    && item.getAdjustDocId() == null && StrUtil.isBlank(item.getNote())) {
                throw new BizException(String.format(
                        "账户「%s」差异 %s 还没处理:生成资金调整单(唯一出口)或填写差异说明留痕",
                        item.getRefName(), plain(item.getDiffAmount())));
            }
            if (CashCheckItem.TYPE_PAYABLE.equals(item.getItemType())
                    && StrUtil.isBlank(item.getExitAction()) && StrUtil.isBlank(item.getNote())) {
                throw new BizException(String.format(
                        "供应商「%s」应付差异 %s 还没处理:选「补录」或「红冲」出口,或填写差异说明留痕",
                        item.getRefName(), plain(item.getDiffAmount())));
            }
        }
        // 防双击收口:条件更新(同 DocStatusGuard 模式),后到者受影响行数=0 抛"已被处理"
        int updated = checkMapper.update(null, new LambdaUpdateWrapper<CashCheck>()
                .eq(CashCheck::getId, checkId)
                .eq(CashCheck::getCheckStatus, CashCheck.ST_IN_PROGRESS)
                .set(CashCheck::getCheckStatus, CashCheck.ST_DONE)
                .set(CashCheck::getConfirmBy, userId)
                .set(CashCheck::getConfirmAt, LocalDateTime.now())
                .set(CashCheck::getUpdateUser, userId));
        if (updated != 1) {
            throw new BizException(String.format("钱盘核对[%s]已被处理(他人已完成),请刷新查看", check.getCheckNo()));
        }
        opLogService.record(operator, "完成钱盘核对", "cash_check", checkId,
                CashCheck.ST_IN_PROGRESS, CashCheck.ST_DONE);
    }

    /** 作废(未完成的核对可以废弃重来;记录不删,状态置已作废) */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long checkId, Long userId, String operator) {
        CashCheck check = mustGetInProgress(checkId);
        check.setCheckStatus(CashCheck.ST_VOID);
        check.setUpdateUser(userId);
        checkMapper.updateById(check);
        opLogService.record(operator, "作废钱盘核对", "cash_check", checkId, null, check.getCheckNo());
    }

    // ============================== 查询 ==============================

    public CheckDetailResp detail(Long checkId) {
        CashCheck check = mustGet(checkId);
        CheckDetailResp resp = new CheckDetailResp();
        resp.setId(check.getId());
        resp.setCheckNo(check.getCheckNo());
        resp.setCheckPeriod(check.getCheckPeriod());
        resp.setCheckStatus(check.getCheckStatus());
        resp.setSettleModeSnap(check.getSettleModeSnap());
        resp.setPlatformSkipped(check.getPlatformSkipped());
        resp.setPlatformNote(check.getPlatformNote());
        resp.setRemark(check.getRemark());
        resp.setConfirmAt(check.getConfirmAt());
        resp.setCreateTime(check.getCreateTime());
        for (CashCheckItem item : itemsOf(checkId)) {
            CheckItemRow row = toRow(item);
            if (CashCheckItem.TYPE_ACCOUNT.equals(item.getItemType())) {
                resp.getAccountItems().add(row);
            } else if (CashCheckItem.TYPE_PLATFORM.equals(item.getItemType())) {
                resp.getPlatformItems().add(row);
            } else {
                resp.getPayableItems().add(row);
            }
        }
        return resp;
    }

    public List<CheckListRow> list(int limit) {
        List<CashCheck> checks = checkMapper.selectList(new LambdaQueryWrapper<CashCheck>()
                .orderByDesc(CashCheck::getId).last("LIMIT " + (limit <= 0 ? 12 : limit)));
        return checks.stream().map(c -> {
            CheckListRow row = new CheckListRow();
            row.setId(c.getId());
            row.setCheckNo(c.getCheckNo());
            row.setCheckPeriod(c.getCheckPeriod());
            row.setCheckStatus(c.getCheckStatus());
            row.setPlatformSkipped(c.getPlatformSkipped());
            row.setCreateTime(c.getCreateTime());
            row.setConfirmAt(c.getConfirmAt());
            row.setDiffCount(itemMapper.selectCount(new LambdaQueryWrapper<CashCheckItem>()
                    .eq(CashCheckItem::getCheckId, c.getId())
                    .isNotNull(CashCheckItem::getDiffAmount)
                    .ne(CashCheckItem::getDiffAmount, BigDecimal.ZERO)));
            return row;
        }).collect(Collectors.toList());
    }

    /** 当前进行中的核对(前端续盘入口;没有返回 null) */
    public CheckDetailResp current() {
        CashCheck check = checkMapper.selectOne(new LambdaQueryWrapper<CashCheck>()
                .eq(CashCheck::getCheckStatus, CashCheck.ST_IN_PROGRESS)
                .orderByDesc(CashCheck::getId).last("LIMIT 1"));
        return check == null ? null : detail(check.getId());
    }

    // ============================== 内部 ==============================

    private void insertItem(Long checkId, String type, Long refId, String refName,
                            BigDecimal system, Long sourceDocId, Long userId) {
        CashCheckItem item = new CashCheckItem();
        item.setCheckId(checkId);
        item.setItemType(type);
        item.setRefId(refId);
        item.setRefName(refName);
        item.setSystemAmount(system == null ? BigDecimal.ZERO : system);
        item.setSourceDocId(sourceDocId);
        item.setCreateUser(userId);
        itemMapper.insert(item);
    }

    private List<CashCheckItem> itemsOf(Long checkId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CashCheckItem>()
                .eq(CashCheckItem::getCheckId, checkId).orderByAsc(CashCheckItem::getId));
    }

    private CheckItemRow toRow(CashCheckItem item) {
        CheckItemRow row = new CheckItemRow();
        row.setId(item.getId());
        row.setItemType(item.getItemType());
        row.setRefId(item.getRefId());
        row.setRefName(item.getRefName());
        row.setSystemAmount(item.getSystemAmount());
        row.setActualAmount(item.getActualAmount());
        row.setDiffAmount(item.getDiffAmount());
        row.setAdjustDocId(item.getAdjustDocId());
        row.setExitAction(item.getExitAction());
        row.setSourceDocId(item.getSourceDocId());
        row.setNote(item.getNote());
        return row;
    }

    private CashCheck mustGet(Long checkId) {
        CashCheck check = checkMapper.selectById(checkId);
        if (check == null) {
            throw new BizException("钱盘核对记录不存在:" + checkId);
        }
        return check;
    }

    private CashCheck mustGetInProgress(Long checkId) {
        CashCheck check = mustGet(checkId);
        if (!CashCheck.ST_IN_PROGRESS.equals(check.getCheckStatus())) {
            throw new BizException(String.format("钱盘核对[%s]状态为[%s],仅进行中可操作",
                    check.getCheckNo(), check.getCheckStatus()));
        }
        return check;
    }

    private CashCheckItem mustGetItem(Long checkId, Long itemId) {
        CashCheckItem item = itemMapper.selectById(itemId);
        if (item == null || !checkId.equals(item.getCheckId())) {
            throw new BizException("核对行不属于本核对记录:" + itemId);
        }
        return item;
    }

    private String generateCheckNo(LocalDate day) {
        String like = "QP-" + day.format(DAY) + "-";
        Long count = checkMapper.selectCount(new LambdaQueryWrapper<CashCheck>()
                .likeRight(CashCheck::getCheckNo, like));
        return like + String.format("%03d", count + 1);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    /** 非零判断(null 视为 0) */
    private static boolean nzSig(BigDecimal v) {
        return v != null && v.signum() != 0;
    }
}
