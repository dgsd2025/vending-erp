package top.aole.vend.modules.stock.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.doc.domain.DocStateMachine;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.mapper.DocItemMapper;
import top.aole.vend.modules.doc.service.DocStatusGuard;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;
import top.aole.vend.modules.stock.mapper.MachineStockSnapshotMapper;
import top.aole.vend.modules.stock.mapper.SaleRecordMapper;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 两级库存引擎(查询/快照/碰撞/冲抵)。
 *
 * 两级两策略(开发铁律#2):
 * - 仓库库存 = Σ stock_ledger 流水(唯一写手=单据确认事件,本类**没有任何库存写方法**);
 * - 机器库存 = 最近快照 + 快照后按业务时间戳的增量推算(转移单+ / 出货-),
 *   出货口径=正常+兑换(不含测试),与导入顺序无关(修穿行场景11乱序导入)。
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockLedgerMapper stockLedgerMapper;
    private final MachineStockSnapshotMapper snapshotMapper;
    private final SaleRecordMapper saleRecordMapper;
    private final DocHeadMapper docHeadMapper;
    private final DocItemMapper docItemMapper;
    private final DocStatusGuard docStatusGuard;
    private final OpLogService opLogService;
    private final StockLedgerWriter ledgerWriter;

    // ============================== 仓库库存 ==============================

    /** 仓库库存 = Σ流水 */
    public BigDecimal getWarehouseStock(Long skuId) {
        BigDecimal sum = stockLedgerMapper.sumWarehouse(skuId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 仓库库存批量版:未出现过流水的 SKU 返回 0 */
    public Map<Long, BigDecimal> getWarehouseStockBatch(Collection<Long> skuIds) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        if (skuIds == null || skuIds.isEmpty()) {
            return result;
        }
        for (Long skuId : skuIds) {
            result.put(skuId, BigDecimal.ZERO);
        }
        for (Map<String, Object> row : stockLedgerMapper.sumWarehouseBatch(skuIds)) {
            result.put(((Number) row.get("productId")).longValue(),
                    new BigDecimal(row.get("qty").toString()));
        }
        return result;
    }

    // ============================== 机器库存(锚点+增量) ==============================

    /**
     * 机器库存 = 最近快照 + 快照后(按 biz_time)转移单增量 − 快照后出货(正常+兑换)。
     * 无快照时锚点=0、全量流水参与推算。后导入的早时间戳数据同样落在正确区间(乱序安全)。
     */
    public BigDecimal getMachineStock(Long machineId, Long skuId) {
        MachineStockSnapshot snap = snapshotMapper.latest(machineId, skuId);
        BigDecimal base = snap == null ? BigDecimal.ZERO : snap.getQty();
        LocalDateTime after = snap == null ? null : snap.getSnapshotTime();
        BigDecimal transfer = stockLedgerMapper.sumMachineTransferAfter(machineId, skuId, after);
        BigDecimal outbound = saleRecordMapper.sumOutboundAfter(machineId, skuId, after);
        return base.add(transfer).subtract(outbound);
    }

    /** 全机汇总:该机器出现过的全部 SKU 各自推算 */
    public Map<Long, BigDecimal> getMachineStockAll(Long machineId) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (Long productId : stockLedgerMapper.distinctMachineProducts(machineId)) {
            result.put(productId, getMachineStock(machineId, productId));
        }
        return result;
    }

    /**
     * 落一张机器库存快照(锚点)。合法来源仅三种:后台缺货页/盘点/补货记录。
     * 快照不是流水,不受"唯一写手"约束;导入通道(M1-3 步骤⑥)与盘点(M3)调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long recordMachineSnapshot(Long machineId, Long productId, String slotNo,
                                      BigDecimal qty, String source, LocalDateTime snapshotTime,
                                      Long userId) {
        MachineStockSnapshot snap = new MachineStockSnapshot();
        snap.setMachineId(machineId);
        snap.setProductId(productId);
        snap.setSlotNo(slotNo);
        snap.setQty(qty);
        snap.setSnapshotSource(source);
        snap.setSnapshotTime(snapshotTime);
        snap.setCreateUser(userId);
        snapshotMapper.insert(snap);
        return snap.getId();
    }

    // ============================== 碰撞检查(P0-3) ==============================

    /**
     * 手工转移碰撞检查:同机器+SKU+日期已存在**导入生成**的出库上架单 → 返回冲突单据 ID 列表。
     * 空列表=无冲突。用途:后台已有出货禁止手工再录(保存/确认时都应调)。
     */
    public List<Long> checkManualTransferCollision(Long machineId, Long skuId, LocalDate bizDate) {
        List<DocHead> importDocs = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getDocType, DocType.TRANSFER_OUT)
                .eq(DocHead::getDocSource, "导入")
                .eq(DocHead::getMachineId, machineId)
                .eq(DocHead::getBizDate, bizDate)
                .notIn(DocHead::getDocStatus, DocStatus.VOID, DocStatus.RED_FLUSHED));
        if (importDocs.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Long> docIdMap = importDocs.stream()
                .collect(Collectors.toMap(DocHead::getId, DocHead::getId, (a, b) -> a, HashMap::new));
        List<DocItem> hits = docItemMapper.selectList(new LambdaQueryWrapper<DocItem>()
                .in(DocItem::getDocId, docIdMap.keySet())
                .eq(DocItem::getProductId, skuId));
        return hits.stream().map(DocItem::getDocId).distinct().collect(Collectors.toList());
    }

    // ============================== 预挂单冲抵(P0-4,骨架) ==============================

    /**
     * 预挂单冲抵接口(骨架,M1-3 导入通道接上完整匹配逻辑):
     * 给定一张导入生成的已确认转移单,按 机器+SKU+当日窗口 找手工预挂单 → 冲抵:
     *   ① 预挂单 → 已作废 + matched_doc_id 指向导入单;
     *   ② 反向释放预挂单锁定的仓库侧流水(+回去,导入单自己的流水才是真账)。
     *
     * @return 被冲抵的预挂单 ID 列表
     *
     * TODO(M1-3):数量差量记带回(prekit_ticket.qty_takeback)、跨日窗口参数化、部分匹配拆行;
     * TODO(M1-3):定时任务钩子——confirm_at 超 48h 未被冲抵的预挂单自动调
     *   DocService.promotePendingTransfer 转正(见该方法注释)。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> matchPendingTransfer(Long importDocId, Long userId) {
        DocHead importDoc = docHeadMapper.selectById(importDocId);
        if (importDoc == null || importDoc.getDocType() != DocType.TRANSFER_OUT
                || !"导入".equals(importDoc.getDocSource())) {
            throw new BizException("冲抵源必须是导入生成的出库上架单:" + importDocId);
        }
        if (importDoc.getDocStatus() != DocStatus.CONFIRMED) {
            throw new BizException("冲抵源单据尚未确认:" + importDoc.getDocNo());
        }
        List<Long> importSkus = docItemMapper.selectList(new LambdaQueryWrapper<DocItem>()
                        .eq(DocItem::getDocId, importDocId)).stream()
                .map(DocItem::getProductId).collect(Collectors.toList());

        // 当日窗口:同机器+同业务日期的手工预挂单
        List<DocHead> pendings = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getDocType, DocType.TRANSFER_OUT)
                .eq(DocHead::getDocStatus, DocStatus.PRE_PENDING)
                .eq(DocHead::getDocSource, "手工")
                .eq(DocHead::getMachineId, importDoc.getMachineId())
                .eq(DocHead::getBizDate, importDoc.getBizDate()));

        List<Long> matched = new ArrayList<>();
        for (DocHead pending : pendings) {
            List<DocItem> pendingItems = docItemMapper.selectList(
                    new LambdaQueryWrapper<DocItem>().eq(DocItem::getDocId, pending.getId()));
            boolean skuHit = pendingItems.stream().anyMatch(i -> importSkus.contains(i.getProductId()));
            if (!skuHit) {
                continue;
            }
            DocStateMachine.assertTransition(pending.getDocType(), pending.getDocStatus(), DocStatus.VOID);
            String before = JSONUtil.toJsonStr(pending);
            pending.setDocStatus(DocStatus.VOID);
            pending.setMatchedDocId(importDocId);
            pending.setUpdateUser(userId);
            // P0-A 条件更新:并发冲抵/转正同一张预挂单只允许一方成功(防仓库侧双重释放)
            docStatusGuard.updateGuarded(pending, DocStatus.PRE_PENDING);
            // 释放预挂单锁定的仓库侧:反向 + 回去(流水仍挂在预挂单 doc_id 上,可追溯)
            for (DocItem item : pendingItems) {
                ledgerWriter.postWarehouse(pending, item, item.getQty(),
                        importDoc.getBizDate().atStartOfDay());
            }
            opLogService.recordJson(userId, "预挂单冲抵", "doc_head", pending.getId(),
                    before, JSONUtil.toJsonStr(pending));
            matched.add(pending.getId());
        }
        return matched;
    }
}
