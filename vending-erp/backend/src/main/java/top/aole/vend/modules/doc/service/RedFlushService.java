package top.aole.vend.modules.doc.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.mapper.DocQueryMapper;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.stock.service.StockService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 红冲连锁(M1-7,审计 P0-1 / 穿行场景10)。
 *
 * 规则(§13.2-1,效力最高):
 * - 数量错 → 整单红冲,**仅限无下游动作**的已确认单(下游=被预挂单冲抵关联/已进应付结算链/
 *   已被红冲或成本调整过;逐项检查并列出);
 * - 红冲 = 生成等额反向单(doc_type=红冲,red_flush_of=原单),按原单方向反向过账,
 *   **免负库存拦截**(neg_stock_exempt),过账走既有"单据已确认"事件;原单 → 已红冲;
 * - 必过"影响清单"确认页:库存变动/应付影响(M3 前只展示未启用)/毛利影响 → 前端确认后才真执行;
 * - 锁账线之前的单据禁红冲(P0-2):老板角色越权(占位)+强制备注放行;
 * - 已付款单的应付红字(settle_bill.direction=红字 冲下一单)→ M3 实装,见 execute() 内 TODO;
 *   状态机通道已验证不被堵死(采购单 已确认→已红冲 与 已确认→待结算→… 平行,互不封路)。
 */
@Service
@RequiredArgsConstructor
public class RedFlushService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DocService docService;
    private final DocHeadMapper docHeadMapper;
    private final DocQueryMapper docQueryMapper;
    private final StockService stockService;
    private final PeriodLockService periodLockService;

    // ============================== 影响清单(确认页取数) ==============================

    /** 红冲前预览:下游检查 + 库存/应付/毛利影响清单。executable=false 时列出全部拦截原因 */
    public Preview preview(Long originDocId) {
        DocHead origin = mustGet(originDocId);
        DocType type = origin.getDocType();
        Preview p = new Preview();
        p.setOriginDocId(origin.getId());
        p.setOriginDocNo(origin.getDocNo());
        p.setDocType(type.getLabel());
        p.setDocStatus(origin.getDocStatus().getLabel());
        p.setTotalAmount(origin.getTotalAmount());

        // ---------- 下游动作检查(P0-1:整单红冲仅限无下游动作) ----------
        List<String> blockers = new ArrayList<>();
        if (type == DocType.RED_FLUSH || type == DocType.COST_ADJUST || type == DocType.CASH_ADJUST) {
            blockers.add("红冲单/调整单不允许再红冲,请对原业务单据重新操作");
        }
        if (origin.getDocStatus() == DocStatus.PENDING_SETTLE || origin.getDocStatus() == DocStatus.SETTLED) {
            blockers.add(String.format("已进入应付结算链(状态[%s])=有下游动作:红冲需连带应付红字(settle_bill.direction),M3 开通",
                    origin.getDocStatus().getLabel()));
        } else if (origin.getDocStatus() != DocStatus.CONFIRMED) {
            blockers.add(String.format("仅[已确认]状态可整单红冲,当前状态[%s]", origin.getDocStatus().getLabel()));
        }
        List<Map<String, Object>> matched = docQueryMapper.matchedByPending(originDocId);
        if (!matched.isEmpty()) {
            blockers.add("存在被本单冲抵的预挂单(下游动作):" + matched.stream()
                    .map(m -> String.valueOf(m.get("docNo"))).collect(Collectors.joining("、")));
        }
        List<Map<String, Object>> reverses = docQueryMapper.reverseDocsOf(originDocId);
        if (!reverses.isEmpty()) {
            blockers.add("已存在指向本单的红冲/成本调整单(下游动作):" + reverses.stream()
                    .map(m -> m.get("docType") + " " + m.get("docNo")).collect(Collectors.joining("、")));
        }
        p.setBlockers(blockers);
        p.setExecutable(blockers.isEmpty());

        // ---------- 锁账检查(P0-2) ----------
        String period = origin.getBookPeriod() != null
                ? origin.getBookPeriod() : origin.getBizDate().format(PERIOD);
        p.setBookPeriod(period);
        p.setLockedPeriod(periodLockService.isLocked(period));
        p.setLockLine(periodLockService.lockLine());

        // ---------- 库存变动(哪些 SKU 仓库/机器各变多少) ----------
        List<Map<String, Object>> stockChanges = new ArrayList<>();
        for (Map<String, Object> item : docQueryMapper.itemsWithProduct(originDocId)) {
            BigDecimal qty = new BigDecimal(String.valueOf(item.get("qty")));
            Long productId = ((Number) item.get("productId")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", productId);
            row.put("skuCode", item.get("skuCode"));
            row.put("productName", item.get("productName"));
            BigDecimal whDelta = qty.multiply(BigDecimal.valueOf(-type.getWarehouseDirection()));
            BigDecimal mcDelta = qty.multiply(BigDecimal.valueOf(-type.getMachineDirection()));
            row.put("warehouseDelta", whDelta);
            row.put("machineDelta", mcDelta);
            BigDecimal whNow = stockService.getWarehouseStock(productId);
            row.put("warehouseNow", whNow);
            row.put("warehouseAfter", whNow.add(whDelta));
            stockChanges.add(row);
        }
        p.setStockChanges(stockChanges);

        // ---------- 应付影响(M3 前只展示"应付链未启用") ----------
        if (origin.getSupplierId() != null
                && (type == DocType.PURCHASE_IN || type == DocType.RETURN_BACK)) {
            Map<String, Object> payable = new HashMap<>();
            payable.put("enabled", false);
            payable.put("note", "应付链未启用(M3):启用后红冲将连带应付红字(settle_bill.direction=红字)冲下一单;"
                    + "当前应付余额为实时推算(期初+Σ采购−Σ退货−Σ抵扣−Σ付款),红冲落账后自动反映");
            payable.put("wouldReverseAmount",
                    origin.getTotalAmount() == null ? BigDecimal.ZERO : origin.getTotalAmount().negate());
            p.setPayableImpact(payable);
        }

        // ---------- 毛利影响(涉及月份) ----------
        List<Map<String, Object>> marginImpacts = new ArrayList<>();
        for (Map<String, Object> row : docQueryMapper.ledgerByPeriod(originDocId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", row.get("period"));
            m.put("locationType", row.get("locationType"));
            BigDecimal amount = new BigDecimal(String.valueOf(row.get("amount")));
            m.put("costAmountReversal", amount.negate()); // 红冲反向后该月存货成本金额变动
            marginImpacts.add(m);
        }
        p.setMarginImpacts(marginImpacts);
        p.setMarginNote("毛利=实收−移动加权成本(§13.1 定死口径)。红冲反向流水落在**红冲当日**,"
                + "不改写历史月流水;已售部分成本不追溯,加权成本自红冲时点起重算");
        return p;
    }

    // ============================== 执行 ==============================

    /** 旧签名兼容(无角色头场景):bossOverride=true 时会因角色缺失被拒(P1-2) */
    @Transactional(rollbackFor = Exception.class)
    public Long execute(Long originDocId, Long userId, String reason, boolean bossOverride) {
        return execute(originDocId, userId, reason, bossOverride, null);
    }

    /**
     * 执行整单红冲:前端必须先展示 preview 影响清单、用户确认后才调本方法。
     * 服务端再跑一遍全部检查(不信任前端),生成反向单 → 确认过账(免拦截)→ 原单已红冲。
     *
     * @param role 操作者角色(X-User-Role 头占位,盲审 P1-2):bossOverride=true 必须是老板角色,
     *             与解锁同一把尺子(PeriodLockService.assertBossRole),SSO 接入时统一替换。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long execute(Long originDocId, Long userId, String reason, boolean bossOverride, String role) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new BizException("红冲必须填写原因(影响清单确认页备注)");
        }
        Preview p = preview(originDocId);
        if (!p.isExecutable()) {
            throw new BizException("红冲被拦截:" + String.join(";", p.getBlockers()));
        }
        // P1-2:老板越权先验角色头(与解锁同逻辑),不许光传个 bossOverride=true 就放行
        if (bossOverride) {
            periodLockService.assertBossRole(role, "锁账期红冲越权");
        }
        // P0-2:锁账线之前的单据禁红冲;老板越权(角色已验)+强制备注放行
        periodLockService.assertPeriodOperable(p.getBookPeriod(), "红冲", bossOverride, reason);

        DocHead origin = mustGet(originDocId);
        // 生成等额反向单:明细 1:1 复制(含 po_item_id → PurchaseReceiptListener 红冲分支回冲已收量)
        DocCreateReq req = new DocCreateReq();
        req.setDocType(DocType.RED_FLUSH);
        req.setBizDate(LocalDate.now());
        req.setMachineId(origin.getMachineId());
        req.setSupplierId(origin.getSupplierId());
        req.setPurchaseOrderId(origin.getPurchaseOrderId());
        req.setRedFlushOf(originDocId); // docSource 由 createDoc 服务端裁决=手工(P1-5)
        req.setRemark(String.format("红冲原单[%s]%s。原因:%s",
                origin.getDocNo(), p.isLockedPeriod() ? "(锁账期老板越权)" : "", reason.trim()));
        List<DocItemReq> items = new ArrayList<>();
        for (DocItem item : docService.itemsOf(originDocId)) {
            DocItemReq ir = new DocItemReq();
            ir.setProductId(item.getProductId());
            ir.setSlotNo(item.getSlotNo());
            ir.setBoxQty(item.getBoxQty());
            ir.setQty(item.getQty());
            ir.setUnitPrice(item.getUnitPrice());
            ir.setBatchNo(item.getBatchNo());
            ir.setExpireDate(item.getExpireDate());
            ir.setPoItemId(item.getPoItemId());
            ir.setRemark("红冲 " + origin.getDocNo());
            items.add(ir);
        }
        req.setItems(items);
        Long redDocId = docService.createDoc(req, userId);
        docService.submit(redDocId, userId);
        // 确认=过账触发:StockPostingListener 红冲分支按原单方向反向写流水(免负库存拦截,neg_stock_exempt 默认1);
        // PurchaseReceiptListener 红冲分支回冲订货单已收量(在途恢复)。同事务,任一失败整体回滚。
        docService.confirm(redDocId, userId, true, null);

        // TODO(M3 应付红字通道):原单若已付款/已结算,红冲需连带生成 settle_bill(direction=红字)冲下一单。
        //   M1-7 状态机已验证通道不堵死(PENDING_SETTLE/SETTLED 在 preview 中被列为下游动作拦截,
        //   M3 把该拦截替换为"红冲+自动生成应付红字"的连锁事务)。

        docService.markRedFlushed(originDocId, userId, reason.trim());
        return redDocId;
    }

    private DocHead mustGet(Long docId) {
        DocHead head = docHeadMapper.selectById(docId);
        if (head == null) {
            throw new BizException("单据不存在:" + docId);
        }
        return head;
    }

    /** 影响清单确认页数据(穿行场景10:红冲必过确认页) */
    @Data
    public static class Preview {
        private Long originDocId;
        private String originDocNo;
        private String docType;
        private String docStatus;
        private BigDecimal totalAmount;
        /** 下游动作/状态拦截原因(空=可红冲) */
        private List<String> blockers;
        private boolean executable;
        /** 原单入账月 + 是否已锁 + 当前锁账线(已锁→前端要求老板越权+备注) */
        private String bookPeriod;
        private boolean lockedPeriod;
        private String lockLine;
        /** 库存变动:每 SKU 仓库/机器各变多少 + 仓库现值/红冲后值 */
        private List<Map<String, Object>> stockChanges;
        /** 应付影响(M3 前 enabled=false 只展示说明) */
        private Map<String, Object> payableImpact;
        /** 毛利影响:原单流水涉及月份的存货成本金额反向 */
        private List<Map<String, Object>> marginImpacts;
        private String marginNote;
    }
}
