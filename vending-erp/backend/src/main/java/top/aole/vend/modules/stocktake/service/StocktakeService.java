package top.aole.vend.modules.stocktake.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Slot;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SlotMapper;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.report.service.CostEngine;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;
import top.aole.vend.modules.stock.service.StockService;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.ConfirmResp;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.CreateReq;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.DetailResp;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.ImportBatchRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.ItemReq;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.ItemRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.ListRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.LossStatRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.OfflineSaleRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.PrecheckResp;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.SaveItemsReq;
import top.aole.vend.modules.stocktake.mapper.StocktakeItemMapper;
import top.aole.vend.modules.stocktake.mapper.StocktakeMapper;
import top.aole.vend.modules.stocktake.mapper.StocktakeQueryMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 盘点服务(M2-4,调研报告 §7.3 问1 + §8.1 盘亏五步流程 M2 段)。
 *
 * 流程:创建(选范围→系统快照账面数存 book_qty)→ 录实盘(只录差异,未录=视同相符)
 *       → 提交 → 确认(五步向导第 3 步:生成盘盈/盘亏单自动过账 + 机器落快照锚点)。
 *
 * 过账口径(两级两策略,开发铁律#2):
 * - 仓库盘点:仓库账只能被单据改 → 全部差异行生成 盘盈入库(+)/盘亏出库(−) 单,确认过账修正 Σledger;
 * - 机器盘点:机器账=推算账,数量校准一律走快照锚点(snapshot_source=盘点,M1-9 缺锚点自愈);
 *   真实损耗行(吞货掉货/过期报损/被盗/原因不明,非豁免)按"退库(机器−→仓库+)+ 盘亏出库(仓库−)"
 *   配对生成——仓库账净不变、机器账由锚点校准、全局成本池按加权成本核减损耗,三本账都对
 *   (若直接对机器差异开仓库盘亏单,会把机器的货亏到仓库账上,且仓库无货时被负库存拦截卡死);
 *   账错类原因(录入错误/盘点错误)与线下豁免行 = 五步第 1 步"是账错,不算损耗",只校锚点不开财务单;
 *   盘盈行(实盘>账面)多为缺锚点导致的推算偏低,由锚点吸收,不虚增资产(真实多货应补录转移单)。
 *
 * 守卫:差异行原因必选;单笔差异成本额>¥50 需老板确认(X-User-Role 占位同红冲);
 *       锁账期(快照月≤锁账线)确认走 period 守卫,老板可越权带备注。
 */
@Service
@RequiredArgsConstructor
public class StocktakeService {

    /** 单笔差异成本额老板确认阈值(¥) */
    public static final BigDecimal BOSS_CONFIRM_THRESHOLD = new BigDecimal("50");

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int PRECHECK_DAYS = 7;

    private final StocktakeMapper stocktakeMapper;
    private final StocktakeItemMapper itemMapper;
    private final StocktakeQueryMapper queryMapper;
    private final ProductMapper productMapper;
    private final MachineMapper machineMapper;
    private final SlotMapper slotMapper;
    private final StockService stockService;
    private final DocService docService;
    private final PeriodLockService periodLockService;
    private final CostEngine costEngine;
    private final OpLogService opLogService;

    // ============================== 创建(快照账面) ==============================

    /** 新建盘点单:按范围全量快照账面数(actual 默认=book → 未录=视同相符) */
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateReq req, Long userId) {
        String scope = req.getScopeType();
        if (!Stocktake.SCOPE_WAREHOUSE.equals(scope) && !Stocktake.SCOPE_MACHINE.equals(scope)) {
            throw new BizException("盘点范围只能是 仓库/机器:" + scope);
        }
        Machine machine = null;
        if (Stocktake.SCOPE_MACHINE.equals(scope)) {
            if (req.getMachineId() == null) {
                throw new BizException("机器盘点必须选择机器");
            }
            machine = machineMapper.selectById(req.getMachineId());
            if (machine == null) {
                throw new BizException("机器不存在:" + req.getMachineId());
            }
        }
        // 同范围只允许一张进行中的盘点(防两张单快照口径互相打架)
        Long active = stocktakeMapper.selectCount(new LambdaQueryWrapper<Stocktake>()
                .eq(Stocktake::getScopeType, scope)
                .eq(Stocktake.SCOPE_MACHINE.equals(scope), Stocktake::getMachineId, req.getMachineId())
                .in(Stocktake::getStStatus, Stocktake.ST_IN_PROGRESS, Stocktake.ST_PENDING));
        if (active != null && active > 0) {
            throw new BizException(String.format("该范围已有进行中的盘点单,请先完成或作废(%s%s)",
                    scope, machine == null ? "" : ":" + machine.getMachineName()));
        }

        LocalDateTime now = LocalDateTime.now();
        Stocktake st = new Stocktake();
        st.setStNo(generateStNo(now.toLocalDate()));
        st.setScopeType(scope);
        st.setMachineId(req.getMachineId());
        st.setSnapshotTime(now);
        st.setStStatus(Stocktake.ST_IN_PROGRESS);
        st.setSourceTask(StrUtil.blankToDefault(req.getSourceTask(), "手动"));
        st.setCreateUser(userId);
        stocktakeMapper.insert(st);

        // 快照账面:仓库=Σledger;机器=锚点+增量推算(可为负=缺锚点,红灯提示实盘校准)
        Map<Long, BigDecimal> book;
        Map<Long, String> slotOf = new LinkedHashMap<>();
        if (Stocktake.SCOPE_WAREHOUSE.equals(scope)) {
            List<Long> productIds = productMapper.selectList(new LambdaQueryWrapper<Product>()
                            .select(Product::getId).orderByAsc(Product::getId))
                    .stream().map(Product::getId).collect(Collectors.toList());
            book = stockService.getWarehouseStockBatch(productIds);
        } else {
            book = new LinkedHashMap<>(stockService.getMachineStockAll(req.getMachineId()));
            // 货道已绑但从未有流水/快照的 SKU 也纳入(账面 0,现场可能盘出实货)
            for (Slot slot : slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                    .eq(Slot::getMachineId, req.getMachineId()))) {
                if (slot.getProductId() != null) {
                    book.putIfAbsent(slot.getProductId(), BigDecimal.ZERO);
                    slotOf.putIfAbsent(slot.getProductId(), slot.getSlotNo());
                }
            }
        }
        for (Map.Entry<Long, BigDecimal> e : book.entrySet()) {
            StocktakeItem item = new StocktakeItem();
            item.setStocktakeId(st.getId());
            item.setProductId(e.getKey());
            item.setSlotNo(slotOf.get(e.getKey()));
            item.setBookQty(e.getValue());
            item.setActualQty(e.getValue()); // 未录=视同相符
            item.setDiffQty(BigDecimal.ZERO);
            item.setOfflineExempt(false);
            item.setCreateUser(userId);
            itemMapper.insert(item);
        }
        opLogService.recordJson(userId, "新建盘点", "stocktake", st.getId(), null, JSONUtil.toJsonStr(st));
        return st.getId();
    }

    // ============================== 录实盘(只录差异) ==============================

    /**
     * 录入实盘:整包替换差异集——先全部复位为相符(actual=book),再应用提交的差异行。
     * 差异行(actual≠book)必选原因;可反复保存直到提交。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveItems(Long id, SaveItemsReq req, Long userId) {
        Stocktake st = mustGet(id);
        if (!Stocktake.ST_IN_PROGRESS.equals(st.getStStatus())) {
            throw new BizException(String.format("盘点单[%s]状态为[%s],仅进行中可录实盘", st.getStNo(), st.getStStatus()));
        }
        Map<Long, StocktakeItem> byProduct = itemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                        .eq(StocktakeItem::getStocktakeId, id))
                .stream().collect(Collectors.toMap(StocktakeItem::getProductId, i -> i));

        // 复位:未提交的行=视同相符
        for (StocktakeItem item : byProduct.values()) {
            item.setActualQty(item.getBookQty());
            item.setDiffQty(BigDecimal.ZERO);
            item.setDiffReason(null);
            item.setDiffAmount(null);
            item.setOfflineExempt(false);
        }
        for (ItemReq row : req.getRows()) {
            StocktakeItem item = byProduct.get(row.getProductId());
            if (item == null) {
                throw new BizException("商品不在本盘点单快照范围内:" + row.getProductId());
            }
            if (row.getActualQty() == null || row.getActualQty().signum() < 0) {
                throw new BizException("实盘数不能为负:商品 " + row.getProductId());
            }
            item.setActualQty(row.getActualQty());
            item.setDiffQty(row.getActualQty().subtract(item.getBookQty()));
            if (item.getDiffQty().signum() != 0) {
                if (StrUtil.isBlank(row.getDiffReason())) {
                    throw new BizException(String.format("差异行必选原因(商品 %d 差异 %s):%s",
                            row.getProductId(), plain(item.getDiffQty()),
                            String.join("/", StocktakeItem.VALID_REASONS)));
                }
                if (!StocktakeItem.VALID_REASONS.contains(row.getDiffReason())) {
                    throw new BizException("非法差异原因:" + row.getDiffReason()
                            + "(可选:" + String.join("/", StocktakeItem.VALID_REASONS) + ")");
                }
                item.setDiffReason(row.getDiffReason());
                item.setOfflineExempt(Boolean.TRUE.equals(row.getOfflineExempt()));
            }
        }
        for (StocktakeItem item : byProduct.values()) {
            item.setUpdateUser(userId);
            itemMapper.updateById(item);
        }
        opLogService.recordJson(userId, "录实盘", "stocktake", id, null,
                "{\"rows\":" + req.getRows().size() + "}");
    }

    /** 提交:进行中 → 待确认(校验全部差异行有原因) */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id, Long userId) {
        Stocktake st = mustGet(id);
        if (!Stocktake.ST_IN_PROGRESS.equals(st.getStStatus())) {
            throw new BizException(String.format("盘点单[%s]状态为[%s],仅进行中可提交", st.getStNo(), st.getStStatus()));
        }
        assertReasonsComplete(id);
        st.setStStatus(Stocktake.ST_PENDING);
        st.setUpdateUser(userId);
        stocktakeMapper.updateById(st);
        opLogService.recordJson(userId, "提交盘点", "stocktake", id, null, JSONUtil.toJsonStr(st));
    }

    /** 作废:仅进行中/待确认(未过账)可作废 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, Long userId) {
        Stocktake st = mustGet(id);
        if (Stocktake.ST_DONE.equals(st.getStStatus())) {
            throw new BizException(String.format("盘点单[%s]已完成,不能作废(盘盈亏单请走红冲)", st.getStNo()));
        }
        st.setStStatus(Stocktake.ST_VOID);
        st.setUpdateUser(userId);
        stocktakeMapper.updateById(st);
        opLogService.recordJson(userId, "作废盘点", "stocktake", id, null, JSONUtil.toJsonStr(st));
    }

    // ============================== 五步第 1 步:系统自动查账 ==============================

    /** 先查账(免冤枉人):近 7 天导入批次/单据变更/线下补录 + "可能漏导"提示清单 */
    public PrecheckResp precheck(Long id) {
        Stocktake st = mustGet(id);
        LocalDateTime since = LocalDateTime.now().minusDays(PRECHECK_DAYS);
        PrecheckResp resp = new PrecheckResp();
        resp.setImports7d(queryMapper.recentImports(since));
        boolean machineScope = Stocktake.SCOPE_MACHINE.equals(st.getScopeType());
        if (machineScope) {
            resp.setDocs7d(queryMapper.recentMachineDocs(st.getMachineId(), since));
            resp.setOfflineSales(queryMapper.offlineSales(st.getMachineId(), since));
        } else {
            resp.setDocs7d(queryMapper.recentWarehouseDocs(since));
        }

        boolean hasReplenishImport = false;
        boolean hasSaleImport = false;
        for (ImportBatchRow row : resp.getImports7d()) {
            if (ImportBatch.STATUS_IMPORTED.equals(row.getBatchStatus())) {
                hasReplenishImport |= ImportBatch.TYPE_REPLENISH.equals(row.getFileType());
                hasSaleImport |= ImportBatch.TYPE_SALE.equals(row.getFileType());
            }
        }
        if (machineScope && !hasReplenishImport) {
            resp.getHints().add("近 7 天未导入「系统补货记录」——机器账可能漏导补货,先补导再归因(五步第 1 步)");
        }
        if (!hasSaleImport) {
            resp.getHints().add("近 7 天未导入「出货明细」——账面可能偏高(销量没扣),先补导再归因");
        }
        if (!resp.getOfflineSales().isEmpty()) {
            BigDecimal qty = resp.getOfflineSales().stream().map(OfflineSaleRow::getQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            resp.getHints().add(String.format(
                    "该机近 7 天有 %s 件线下补录销售——机器推算账不含线下出货,对应差异行可勾选「线下豁免」不算损耗", plain(qty)));
        }
        if (machineScope) {
            long negBook = itemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                            .eq(StocktakeItem::getStocktakeId, id)).stream()
                    .filter(i -> i.getBookQty().signum() < 0).count();
            if (negBook > 0) {
                resp.getHints().add(String.format(
                        "%d 个 SKU 账面为负=缺快照锚点(M1-9 红灯),请务必实盘录入——确认后锚点自动校准推算账", negBook));
            }
        }
        if (resp.getHints().isEmpty()) {
            resp.getHints().add("近 7 天导入与单据无漏导迹象——账没错,是真差异,请归因(第 2 步)");
        }
        return resp;
    }

    // ============================== 确认(第 3 步:生成单据 + 锚点) ==============================

    /**
     * 确认盘点:锁账守卫 → 原因/老板阈值校验 → 差异行按加权成本计价 →
     * 仓库范围生成盘盈/盘亏单,机器范围生成"退库+盘亏"配对单 → 机器落快照锚点 → 已完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfirmResp confirm(Long id, Long userId, String role, boolean bossOverride, String note) {
        Stocktake st = mustGet(id);
        if (!Stocktake.ST_PENDING.equals(st.getStStatus())) {
            throw new BizException(String.format("盘点单[%s]状态为[%s],请先提交再确认", st.getStNo(), st.getStStatus()));
        }
        // 锁账守卫(P0-2):快照月已锁 → 拦;老板越权带备注放行(生成的盘盈亏单入当月)
        periodLockService.assertPeriodOperable(st.getSnapshotTime().format(PERIOD), "盘点确认", bossOverride, note);
        assertReasonsComplete(id);

        List<StocktakeItem> items = itemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                .eq(StocktakeItem::getStocktakeId, id).orderByAsc(StocktakeItem::getId));
        List<StocktakeItem> diffs = items.stream()
                .filter(i -> i.getDiffQty().signum() != 0).collect(Collectors.toList());

        // 差异计价:移动加权成本(CostEngine 与毛利报表同源);无成本史 SKU=NULL(显「—」,禁 0 加权)
        Map<Long, CostEngine.Pool> pools = costEngine.replay().getPools();
        List<String> bossRows = new ArrayList<>();
        for (StocktakeItem item : diffs) {
            CostEngine.Pool pool = pools.get(item.getProductId());
            BigDecimal avg = pool == null ? null : pool.currentAvg();
            item.setDiffAmount(avg == null ? null
                    : item.getDiffQty().multiply(avg).setScale(2, RoundingMode.HALF_UP));
            if (item.getDiffAmount() != null
                    && item.getDiffAmount().abs().compareTo(BOSS_CONFIRM_THRESHOLD) > 0) {
                bossRows.add(String.format("商品%d 差异%s 金额%s", item.getProductId(),
                        plain(item.getDiffQty()), plain(item.getDiffAmount())));
            }
        }
        // 单笔差异成本额>¥50 标红需老板确认(占位角色头,同红冲一把尺子 P1-2)
        if (!bossRows.isEmpty()) {
            try {
                periodLockService.assertBossRole(role, "盘点差异确认(单笔成本额>¥50)");
            } catch (BizException e) {
                throw new BizException(String.format("以下差异行单笔成本额超 ¥%s,需老板角色确认:%s。%s",
                        plain(BOSS_CONFIRM_THRESHOLD), String.join(";", bossRows), e.getMessage()));
            }
        }

        LocalDateTime confirmTime = LocalDateTime.now();
        LocalDate bizDate = confirmTime.toLocalDate();
        ConfirmResp resp = new ConfirmResp();
        resp.setStocktakeId(id);

        if (Stocktake.SCOPE_WAREHOUSE.equals(st.getScopeType())) {
            // 仓库账只能被单据改:全部差异行入盘盈/盘亏单
            List<StocktakeItem> gains = diffs.stream()
                    .filter(i -> i.getDiffQty().signum() > 0).collect(Collectors.toList());
            List<StocktakeItem> losses = diffs.stream()
                    .filter(i -> i.getDiffQty().signum() < 0).collect(Collectors.toList());
            if (!gains.isEmpty()) {
                Long gainDoc = postDoc(DocType.GAIN_IN, null, bizDate, confirmTime, gains, pools,
                        st.getStNo() + " 盘盈自动生成", userId);
                st.setGainDocId(gainDoc);
                resp.setGainDocId(gainDoc);
            }
            if (!losses.isEmpty()) {
                Long lossDoc = postDoc(DocType.LOSS_OUT, null, bizDate, confirmTime, losses, pools,
                        st.getStNo() + " 盘亏自动生成", userId);
                st.setLossDocId(lossDoc);
                resp.setLossDocId(lossDoc);
            }
        } else {
            // 机器范围:真实损耗行(非账错原因、非线下豁免)走 退库+盘亏 配对;数量校准统一由锚点完成
            List<StocktakeItem> realLosses = diffs.stream()
                    .filter(i -> i.getDiffQty().signum() < 0)
                    .filter(i -> !StocktakeItem.ACCOUNT_ERROR_REASONS.contains(i.getDiffReason()))
                    .filter(i -> !Boolean.TRUE.equals(i.getOfflineExempt()))
                    .collect(Collectors.toList());
            if (!realLosses.isEmpty()) {
                Long returnDoc = postDoc(DocType.RETURN_BACK, st.getMachineId(), bizDate, confirmTime,
                        realLosses, pools, st.getStNo() + " 机器盘亏取出核销(配对退库)", userId);
                Long lossDoc = postDoc(DocType.LOSS_OUT, st.getMachineId(), bizDate, confirmTime,
                        realLosses, pools, st.getStNo() + " 机器盘亏自动生成(配对退库单#" + returnDoc + ")", userId);
                st.setLossDocId(lossDoc);
                resp.setReturnDocId(returnDoc);
                resp.setLossDocId(lossDoc);
            }
            // 锚点校准(开发铁律:机器盘点确认后必落快照,source=盘点):实盘≥0 的行全部落锚
            int anchors = 0;
            for (StocktakeItem item : items) {
                if (item.getActualQty().signum() >= 0) {
                    stockService.recordMachineSnapshot(st.getMachineId(), item.getProductId(),
                            item.getSlotNo(), item.getActualQty(),
                            MachineStockSnapshot.SRC_STOCKTAKE, confirmTime, userId);
                    anchors++;
                } else {
                    resp.getSkippedAnchorProducts().add(item.getProductId());
                }
            }
            resp.setAnchorCount(anchors);
        }

        resp.setLossAmount(sumAmount(diffs, -1));
        resp.setGainAmount(sumAmount(diffs, 1));
        for (StocktakeItem item : diffs) {
            item.setUpdateUser(userId);
            itemMapper.updateById(item);
        }
        String before = JSONUtil.toJsonStr(st);
        st.setStStatus(Stocktake.ST_DONE);
        st.setUpdateUser(userId);
        stocktakeMapper.updateById(st);
        opLogService.recordJson(userId, bossOverride ? "确认盘点(老板越权:" + note + ")" : "确认盘点",
                "stocktake", id, before, JSONUtil.toJsonStr(st));
        return resp;
    }

    // ============================== 查询 ==============================

    public List<ListRow> list(int limit) {
        return queryMapper.listRows(limit <= 0 ? 50 : limit);
    }

    public DetailResp detail(Long id) {
        Stocktake st = mustGet(id);
        DetailResp resp = new DetailResp();
        resp.setId(st.getId());
        resp.setStNo(st.getStNo());
        resp.setScopeType(st.getScopeType());
        resp.setMachineId(st.getMachineId());
        if (st.getMachineId() != null) {
            Machine m = machineMapper.selectById(st.getMachineId());
            resp.setMachineName(m == null ? null : m.getMachineName());
        }
        resp.setSnapshotTime(st.getSnapshotTime());
        resp.setStStatus(st.getStStatus());
        resp.setGainDocId(st.getGainDocId());
        resp.setLossDocId(st.getLossDocId());
        resp.setSourceTask(st.getSourceTask());
        List<ItemRow> rows = queryMapper.itemRows(id);
        resp.setItems(rows);
        resp.setDiffCount(rows.stream().filter(r -> r.getDiffQty() != null
                && r.getDiffQty().signum() != 0).count());
        return resp;
    }

    /** 损耗统计:按原因×月份(件数/成本额),近 months 个月,豁免行不算损耗 */
    public List<LossStatRow> lossStats(int months) {
        int m = months <= 0 ? 6 : months;
        return queryMapper.lossStats(LocalDateTime.now().minusMonths(m)
                .withDayOfMonth(1).toLocalDate().atStartOfDay());
    }

    // ============================== 内部 ==============================

    /** 生成并确认一张盘点产物单据(明细=|diff|,单价=移动加权成本,业务时间=确认时刻) */
    private Long postDoc(DocType type, Long machineId, LocalDate bizDate, LocalDateTime bizTime,
                         List<StocktakeItem> rows, Map<Long, CostEngine.Pool> pools,
                         String remark, Long userId) {
        DocCreateReq req = new DocCreateReq();
        req.setDocType(type);
        req.setBizDate(bizDate);
        req.setMachineId(machineId);
        req.setRemark(remark);
        List<DocItemReq> items = new ArrayList<>();
        for (StocktakeItem row : rows) {
            DocItemReq item = new DocItemReq();
            item.setProductId(row.getProductId());
            item.setSlotNo(row.getSlotNo());
            item.setQty(row.getDiffQty().abs());
            CostEngine.Pool pool = pools.get(row.getProductId());
            BigDecimal avg = pool == null ? null : pool.currentAvg();
            item.setUnitPrice(avg == null ? null : avg.setScale(4, RoundingMode.HALF_UP));
            item.setRemark(row.getDiffReason());
            items.add(item);
        }
        req.setItems(items);
        Long docId = docService.createDoc(req, userId);
        docService.submit(docId, userId);
        docService.confirm(docId, userId, false, bizTime);
        return docId;
    }

    /** 差异行原因完整性校验(差异行必选原因) */
    private void assertReasonsComplete(Long id) {
        List<StocktakeItem> missing = itemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                        .eq(StocktakeItem::getStocktakeId, id)).stream()
                .filter(i -> i.getDiffQty().signum() != 0 && StrUtil.isBlank(i.getDiffReason()))
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new BizException("以下差异行未选原因(差异行必选):商品 " + missing.stream()
                    .map(i -> String.valueOf(i.getProductId())).collect(Collectors.joining(",")));
        }
    }

    private BigDecimal sumAmount(List<StocktakeItem> diffs, int sign) {
        return diffs.stream()
                .filter(i -> i.getDiffQty().signum() == sign && i.getDiffAmount() != null)
                .map(StocktakeItem::getDiffAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Stocktake mustGet(Long id) {
        Stocktake st = stocktakeMapper.selectById(id);
        if (st == null) {
            throw new BizException("盘点单不存在:" + id);
        }
        return st;
    }

    private String generateStNo(LocalDate day) {
        String like = "PD-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Long count = stocktakeMapper.selectCount(new LambdaQueryWrapper<Stocktake>()
                .likeRight(Stocktake::getStNo, like));
        return like + String.format("%03d", count + 1);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
