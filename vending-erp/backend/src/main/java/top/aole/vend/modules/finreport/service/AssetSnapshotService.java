package top.aole.vend.modules.finreport.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.claim.domain.entity.Claim;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.service.ClaimService;
import top.aole.vend.modules.finreport.domain.entity.AssetSnapshot;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.mapper.AssetSnapshotMapper;
import top.aole.vend.modules.finreport.mapper.FinReportQueryMapper;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.SettleModeService;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.service.ReportService;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.service.PayableService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 资产快照(M3-6,§13.1 公式效力最高):
 *
 * 净流动资产 = 库存(成本)+ 平台待结算 + 账户现金 + 索赔应收(申请中)− 应付供应商。
 *
 * 取数口全部复用现成公开件(七律禁重造):
 * ① 库存成本 = {@link ReportService#stock()}(仓库+机器 × 移动加权现值);
 * ② 平台待结算 = SettleMode==PLATFORM 时 Σ(正常+退款负 未回填结算单),否则 0(UNSET 横幅传导);
 * ③ 现金 = {@link AccountService#list()} 真实账户余额合计(期初+Σ流水);
 * ④ 索赔应收 = {@link ClaimService#receivable()}(申请中);
 * ⑤ 应付 = {@link PayableService#overview()} 余额合计(期初+Σ采购−退货−抵扣−付款)。
 *
 * 归档纪律:同月重复归档 = 幂等重算覆盖;月份已锁账 → 永不重算(旧报表口径)。
 */
@Service
@RequiredArgsConstructor
public class AssetSnapshotService {

    private static final Pattern PERIOD_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AssetSnapshotMapper snapshotMapper;
    private final FinReportQueryMapper queryMapper;
    private final ReportService reportService;
    private final AccountService accountService;
    private final SettleModeService settleModeService;
    private final ClaimService claimService;
    private final PayableService payableService;
    private final PeriodLockService periodLockService;
    private final OpLogService opLogService;

    // ============================== 即时快照 ==============================

    public FinReportDtos.AssetSnapshotResp current() {
        FinReportDtos.AssetSnapshotResp resp = new FinReportDtos.AssetSnapshotResp();
        resp.setAsOf(LocalDateTime.now());

        // 结算模式(UNSET 横幅传导到 p10)
        MoneyDtos.SettleModeResp mode = settleModeService.get();
        resp.setSettleMode(mode.getMode());
        resp.setSettleBanner(mode.getBanner());

        // ① 库存(成本):仓库+机器,移动加权现值
        ReportDtos.StockResp stock = reportService.stock();
        resp.setDataAsOf(stock.getDataAsOf());
        resp.setInventoryAmount(nz(stock.getTotalAmount()));
        resp.setWarehouseAmount(nz(stock.getWarehouseAmount()));
        resp.setMachineAmount(nz(stock.getMachineAmount()));
        for (ReportDtos.StockRow row : stock.getRows()) {
            if (row.getAmount() == null || row.getAmount().signum() == 0) {
                continue;
            }
            FinReportDtos.InventoryRow r = new FinReportDtos.InventoryRow();
            r.setProductId(row.getProductId());
            r.setCode(row.getCode());
            r.setName(row.getName());
            r.setQty(row.getTotalQty());
            r.setAmount(row.getAmount());
            resp.getInventoryRows().add(r);
        }
        resp.getInventoryRows().sort(Comparator.comparing(FinReportDtos.InventoryRow::getAmount,
                Comparator.reverseOrder()));

        // ② 平台待结算:仅 PLATFORM 模式取数,UNSET/DIRECT 恒 0(附录D)
        if (SettleModeService.MODE_PLATFORM.equals(mode.getMode())) {
            resp.setPlatformPending(scale2(queryMapper.platformPending()));
            for (Map<String, Object> raw : queryMapper.platformPendingByPeriod()) {
                FinReportDtos.PendingRow r = new FinReportDtos.PendingRow();
                r.setPeriod(String.valueOf(raw.get("period")));
                r.setCnt(((Number) raw.get("cnt")).longValue());
                r.setAmount(new BigDecimal(String.valueOf(raw.get("amount"))));
                resp.getPendingRows().add(r);
            }
        } else {
            resp.setPlatformPending(BigDecimal.ZERO);
        }

        // ③ 现金:真实账户余额合计(停用账户历史余额仍计入)
        BigDecimal cash = BigDecimal.ZERO;
        for (MoneyDtos.AccountRow a : accountService.list()) {
            if (Boolean.TRUE.equals(a.getIsVirtual())) {
                continue;
            }
            cash = cash.add(nz(a.getBalance()));
            FinReportDtos.CashRow r = new FinReportDtos.CashRow();
            r.setAccountId(a.getId());
            r.setAccountName(a.getAccountName());
            r.setAccountType(a.getAccountType());
            r.setBalance(a.getBalance());
            r.setDisabled(a.getStatus() != null && a.getStatus() == 0);
            resp.getCashRows().add(r);
        }
        resp.setCashTotal(scale2(cash));

        // ④ 索赔应收(申请中,P0-6)
        resp.setClaimReceivable(scale2(nz(claimService.receivable())));
        for (ClaimDtos.ClaimRow c : claimService.list(Claim.STATUS_PENDING)) {
            FinReportDtos.ClaimItemRow r = new FinReportDtos.ClaimItemRow();
            r.setClaimId(c.getId());
            r.setClaimNo(c.getClaimNo());
            r.setClaimTarget(c.getClaimTarget());
            r.setAmount(c.getAmount());
            r.setCreateTime(c.getCreateTime());
            resp.getClaimRows().add(r);
        }

        // ⑤ 应付供应商合计
        BigDecimal payable = BigDecimal.ZERO;
        for (SettleDtos.SupplierOverviewRow s : payableService.overview()) {
            payable = payable.add(nz(s.getBalance()));
            if (nz(s.getBalance()).signum() == 0) {
                continue;
            }
            FinReportDtos.PayableRow r = new FinReportDtos.PayableRow();
            r.setSupplierId(s.getSupplierId());
            r.setSupplierName(s.getSupplierName());
            r.setBalance(s.getBalance());
            r.setPrepay(s.isPrepay());
            resp.getPayableRows().add(r);
        }
        resp.setPayableTotal(scale2(payable));

        // 净流动资产 = ①+②+③+④−⑤(§13.1)
        resp.setNetAsset(scale2(resp.getInventoryAmount()
                .add(resp.getPlatformPending())
                .add(resp.getCashTotal())
                .add(resp.getClaimReceivable())
                .subtract(resp.getPayableTotal())));

        // 比上月:最近一次归档快照
        AssetSnapshot prev = snapshotMapper.selectOne(new LambdaQueryWrapper<AssetSnapshot>()
                .orderByDesc(AssetSnapshot::getPeriod).last("LIMIT 1"));
        if (prev != null) {
            resp.setPrevPeriod(prev.getPeriod());
            resp.setPrevNetAsset(prev.getNetAsset());
        }
        return resp;
    }

    // ============================== 月度归档 ==============================

    /**
     * 归档(每月 1 日快照上月/当月家底):同月重复调用幂等重算覆盖;
     * 月份已锁账 → 拒绝重算(归档快照与旧报表同纪律:永不重算)。
     */
    @Transactional(rollbackFor = Exception.class)
    public FinReportDtos.SnapshotRow archive(String period, Long userId, String operator) {
        assertPeriodFormat(period);
        String currentMonth = YearMonth.now().format(PERIOD);
        if (period.compareTo(currentMonth) > 0) {
            throw new BizException("不能归档未来月份:" + period);
        }
        AssetSnapshot exist = findByPeriod(period);
        if (periodLockService.isLocked(period)) {
            throw new BizException(String.format(
                    "月份 %s 已锁账(锁账线 %s):归档快照%s(同旧报表\"永不重算\"纪律;确需补档请老板先解锁)",
                    period, periodLockService.lockLine(),
                    exist != null ? "已定格,不重算" : "无法补录"));
        }
        FinReportDtos.AssetSnapshotResp now = current();
        AssetSnapshot snap = exist != null ? exist : new AssetSnapshot();
        snap.setPeriod(period);
        snap.setInventoryAmount(now.getInventoryAmount());
        snap.setPlatformPending(now.getPlatformPending());
        snap.setCashTotal(now.getCashTotal());
        snap.setClaimReceivable(now.getClaimReceivable());
        snap.setPayableTotal(now.getPayableTotal());
        snap.setNetAsset(now.getNetAsset());
        snap.setDetailJson(buildDetailJson(now));
        if (exist == null) {
            snap.setCreateUser(userId);
            snapshotMapper.insert(snap);
        } else {
            snap.setUpdateUser(userId);
            snapshotMapper.updateById(snap);
        }
        opLogService.record(operator, exist == null ? "资产快照归档" : "资产快照重算(幂等覆盖)",
                "asset_snapshot", snap.getId(), null, period + " 净资产 " + snap.getNetAsset());
        return toRow(snap);
    }

    /** 归档列表(新→旧,p10 月度归档表) */
    public List<FinReportDtos.SnapshotRow> archives() {
        List<FinReportDtos.SnapshotRow> rows = new ArrayList<>();
        for (AssetSnapshot s : snapshotMapper.selectList(new LambdaQueryWrapper<AssetSnapshot>()
                .orderByDesc(AssetSnapshot::getPeriod))) {
            rows.add(toRow(s));
        }
        return rows;
    }

    /** 趋势(旧→新,近 N 月净资产折线数据) */
    public List<FinReportDtos.SnapshotRow> trend(int months) {
        List<FinReportDtos.SnapshotRow> all = archives();
        java.util.Collections.reverse(all);
        int n = Math.min(Math.max(months, 1), 60);
        return all.size() <= n ? all : all.subList(all.size() - n, all.size());
    }

    // ============================== 内部 ==============================

    private AssetSnapshot findByPeriod(String period) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<AssetSnapshot>()
                .eq(AssetSnapshot::getPeriod, period).last("LIMIT 1"));
    }

    private FinReportDtos.SnapshotRow toRow(AssetSnapshot s) {
        FinReportDtos.SnapshotRow row = new FinReportDtos.SnapshotRow();
        row.setId(s.getId());
        row.setPeriod(s.getPeriod());
        row.setInventoryAmount(s.getInventoryAmount());
        row.setPlatformPending(s.getPlatformPending());
        row.setCashTotal(s.getCashTotal());
        row.setClaimReceivable(s.getClaimReceivable());
        row.setPayableTotal(s.getPayableTotal());
        row.setNetAsset(s.getNetAsset());
        row.setUpdateTime(s.getUpdateTime());
        row.setLocked(periodLockService.isLocked(s.getPeriod()));
        return row;
    }

    /** 下钻明细 JSON(归档留档;库存只留 TOP 50 防 text 爆) */
    private String buildDetailJson(FinReportDtos.AssetSnapshotResp now) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("settleMode", now.getSettleMode());
        detail.put("warehouseAmount", now.getWarehouseAmount());
        detail.put("machineAmount", now.getMachineAmount());
        detail.put("inventoryRows", now.getInventoryRows().subList(0,
                Math.min(50, now.getInventoryRows().size())));
        detail.put("pendingRows", now.getPendingRows());
        detail.put("cashRows", now.getCashRows());
        detail.put("claimRows", now.getClaimRows());
        detail.put("payableRows", now.getPayableRows());
        return JSONUtil.toJsonStr(detail);
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

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
