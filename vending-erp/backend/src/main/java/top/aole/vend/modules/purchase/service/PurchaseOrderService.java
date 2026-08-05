package top.aole.vend.modules.purchase.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrder;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem;
import top.aole.vend.modules.purchase.dto.PoCreateReq;
import top.aole.vend.modules.purchase.mapper.PurchaseOrderItemMapper;
import top.aole.vend.modules.purchase.mapper.PurchaseOrderMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量订货单服务(P0-5):草稿级不进账——订货单自身永不碰 stock_ledger,
 * 它唯一的产出是"在途数"(Σ订购−已收)与收货单的"应收"列。
 *
 * 状态流:草稿 →(下单)已下单 →(收货回写)部分到货 → 已完成
 *        草稿/已下单(未收过货)→ 已取消;部分到货 →(关闭余量)已完成。
 * qty_received 本服务只读——唯一写手是 PurchaseReceiptListener(入库确认事件)。
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    /** 计入在途的状态 */
    private static final Set<String> ACTIVE_STATUS = new HashSet<>(Arrays.asList(
            PurchaseOrder.ST_PLACED, PurchaseOrder.ST_PARTIAL));

    private final PurchaseOrderMapper poMapper;
    private final PurchaseOrderItemMapper poItemMapper;
    private final OpLogService opLogService;

    // ============================== 建单 / 改草稿 ==============================

    @Transactional(rollbackFor = Exception.class)
    public Long create(PoCreateReq req, String operator) {
        PurchaseOrder po = new PurchaseOrder();
        po.setPoNo(generatePoNo());
        po.setSupplierId(req.getSupplierId());
        po.setExpectDate(req.getExpectDate());
        po.setPoStatus(PurchaseOrder.ST_DRAFT);
        po.setRemark(req.getRemark());
        po.setTotalAmount(totalOf(req.getItems()));
        poMapper.insert(po);
        insertItems(po.getId(), req.getItems());
        opLogService.record(operator, "新建订货单", "purchase_order", po.getId(), null, po);
        return po.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateDraft(Long poId, PoCreateReq req, String operator) {
        PurchaseOrder po = mustGet(poId);
        if (!PurchaseOrder.ST_DRAFT.equals(po.getPoStatus())) {
            throw new BizException(String.format("订货单[%s]当前状态[%s],仅草稿可修改", po.getPoNo(), po.getPoStatus()));
        }
        String before = JSONUtil.toJsonStr(po);
        po.setSupplierId(req.getSupplierId());
        po.setExpectDate(req.getExpectDate());
        po.setRemark(req.getRemark());
        po.setTotalAmount(totalOf(req.getItems()));
        poMapper.updateById(po);
        // 草稿改明细 = 整套替换(qty_received 必为 0,物理删无风险)
        poItemMapper.delete(new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getPoId, poId));
        insertItems(poId, req.getItems());
        opLogService.record(operator, "修改订货单", "purchase_order", poId, before, po);
    }

    // ============================== 状态流转 ==============================

    /** 下单:草稿 → 已下单(开始计入在途) */
    @Transactional(rollbackFor = Exception.class)
    public void place(Long poId, String operator) {
        transition(poId, PurchaseOrder.ST_DRAFT, PurchaseOrder.ST_PLACED, "下单", operator);
    }

    /** 取消:仅 草稿/已下单 且未收过货;收过货的用 closeRemaining 关闭余量 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long poId, String operator) {
        PurchaseOrder po = mustGet(poId);
        if (!PurchaseOrder.ST_DRAFT.equals(po.getPoStatus()) && !PurchaseOrder.ST_PLACED.equals(po.getPoStatus())) {
            throw new BizException(String.format("订货单[%s]状态[%s]不可取消(已收过货请用\"关闭余量\")",
                    po.getPoNo(), po.getPoStatus()));
        }
        BigDecimal received = itemsOf(poId).stream().map(PurchaseOrderItem::getQtyReceived)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (received.compareTo(BigDecimal.ZERO) > 0) {
            throw new BizException("订货单已有收货记录,不可取消,请用\"关闭余量\"");
        }
        doTransition(po, PurchaseOrder.ST_CANCELLED, "取消订货单", operator);
    }

    /** 关闭余量:部分到货 → 已完成(剩余在途放弃,不再等货) */
    @Transactional(rollbackFor = Exception.class)
    public void closeRemaining(Long poId, String operator) {
        transition(poId, PurchaseOrder.ST_PARTIAL, PurchaseOrder.ST_DONE, "关闭余量", operator);
    }

    /**
     * 收货回写后重算状态(唯一调用方=PurchaseReceiptListener,同事务):
     * 全部行 已收≥订购 → 已完成;有收但未全收 → 部分到货。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recomputeStatus(Long poId, Long userId) {
        PurchaseOrder po = mustGet(poId);
        if (!ACTIVE_STATUS.contains(po.getPoStatus())) {
            throw new BizException(String.format("订货单[%s]状态[%s]不可收货(需先下单)", po.getPoNo(), po.getPoStatus()));
        }
        List<PurchaseOrderItem> items = itemsOf(poId);
        boolean allDone = items.stream()
                .allMatch(i -> i.getQtyReceived().compareTo(i.getQtyOrdered()) >= 0);
        boolean anyReceived = items.stream()
                .anyMatch(i -> i.getQtyReceived().compareTo(BigDecimal.ZERO) > 0);
        String target = allDone ? PurchaseOrder.ST_DONE
                : anyReceived ? PurchaseOrder.ST_PARTIAL : po.getPoStatus();
        if (!target.equals(po.getPoStatus())) {
            String before = JSONUtil.toJsonStr(po);
            po.setPoStatus(target);
            po.setUpdateUser(userId);
            poMapper.updateById(po);
            opLogService.recordJson(userId, "收货回写→" + target, "purchase_order", poId,
                    before, JSONUtil.toJsonStr(po));
        }
    }

    // ============================== 查询 ==============================

    /** 分页列表,带 已收合计/在途/超期黄灯 */
    public Page<PoVo> page(long current, long size, String poStatus, Long supplierId) {
        Page<PurchaseOrder> page = poMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(StrUtil.isNotBlank(poStatus), PurchaseOrder::getPoStatus, poStatus)
                        .eq(supplierId != null, PurchaseOrder::getSupplierId, supplierId)
                        .orderByDesc(PurchaseOrder::getId));
        Page<PoVo> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toVo).collect(java.util.stream.Collectors.toList()));
        return result;
    }

    public PoVo detail(Long poId) {
        return toVo(mustGet(poId));
    }

    /** 订货明细(带商品名/箱规,详情与收货预填用) */
    public List<Map<String, Object>> itemsWithProduct(Long poId) {
        return poItemMapper.itemsWithProduct(poId);
    }

    /** 单 SKU 在途数(M2 补货公式取数口) */
    public BigDecimal inTransit(Long productId) {
        BigDecimal sum = poItemMapper.sumInTransit(productId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 全 SKU 在途汇总(>0) */
    public List<Map<String, Object>> inTransitAll() {
        return poItemMapper.sumInTransitAll();
    }

    /** 单 SKU 在途明细行(按订货单展开,带超期标记) */
    public List<Map<String, Object>> inTransitLines(Long productId) {
        List<Map<String, Object>> lines = poItemMapper.inTransitLines(productId);
        LocalDate today = LocalDate.now();
        for (Map<String, Object> line : lines) {
            Object expect = line.get("expectDate");
            line.put("overdue", expect != null && LocalDate.parse(expect.toString()).isBefore(today));
        }
        return lines;
    }

    public List<PurchaseOrderItem> itemsOf(Long poId) {
        return poItemMapper.selectList(new LambdaQueryWrapper<PurchaseOrderItem>()
                .eq(PurchaseOrderItem::getPoId, poId).orderByAsc(PurchaseOrderItem::getId));
    }

    public PurchaseOrder mustGet(Long poId) {
        PurchaseOrder po = poMapper.selectById(poId);
        if (po == null) {
            throw new BizException("订货单不存在:" + poId);
        }
        return po;
    }

    // ============================== 内部 ==============================

    private void transition(Long poId, String from, String to, String action, String operator) {
        PurchaseOrder po = mustGet(poId);
        if (!from.equals(po.getPoStatus())) {
            throw new BizException(String.format("订货单[%s]状态[%s]不允许[%s](需为[%s])",
                    po.getPoNo(), po.getPoStatus(), action, from));
        }
        doTransition(po, to, action, operator);
    }

    private void doTransition(PurchaseOrder po, String to, String action, String operator) {
        String before = JSONUtil.toJsonStr(po);
        po.setPoStatus(to);
        poMapper.updateById(po);
        opLogService.record(operator, action, "purchase_order", po.getId(), before, po);
    }

    /** 超期黄灯:预计到货日已过 且 仍在等货(已下单/部分到货) */
    private PoVo toVo(PurchaseOrder po) {
        PoVo vo = new PoVo();
        vo.setPo(po);
        List<PurchaseOrderItem> items = itemsOf(po.getId());
        vo.setItemCount(items.size());
        vo.setQtyOrdered(items.stream().map(PurchaseOrderItem::getQtyOrdered).reduce(BigDecimal.ZERO, BigDecimal::add));
        vo.setQtyReceived(items.stream().map(PurchaseOrderItem::getQtyReceived).reduce(BigDecimal.ZERO, BigDecimal::add));
        boolean active = ACTIVE_STATUS.contains(po.getPoStatus());
        vo.setInTransitQty(active ? vo.getQtyOrdered().subtract(vo.getQtyReceived()) : BigDecimal.ZERO);
        vo.setOverdue(active && po.getExpectDate() != null && po.getExpectDate().isBefore(LocalDate.now()));
        return vo;
    }

    private void insertItems(Long poId, List<PoCreateReq.Item> items) {
        for (PoCreateReq.Item req : items) {
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPoId(poId);
            item.setProductId(req.getProductId());
            item.setQtyOrdered(req.getQtyOrdered());
            item.setQtyReceived(BigDecimal.ZERO);
            item.setUnitPrice(req.getUnitPrice() == null ? BigDecimal.ZERO : req.getUnitPrice());
            item.setAmount(item.getQtyOrdered().multiply(item.getUnitPrice()));
            poItemMapper.insert(item);
        }
    }

    private BigDecimal totalOf(List<PoCreateReq.Item> items) {
        return items.stream()
                .map(i -> i.getQtyOrdered().multiply(i.getUnitPrice() == null ? BigDecimal.ZERO : i.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 订货单号:PO-yyyyMMdd-三位流水 */
    private String generatePoNo() {
        String like = "PO-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Long count = poMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                .likeRight(PurchaseOrder::getPoNo, like));
        return like + String.format("%03d", count + 1);
    }

    /** 列表/详情 VO:订货单 + 汇总数 + 超期黄灯 */
    @Data
    public static class PoVo {
        private PurchaseOrder po;
        private int itemCount;
        private BigDecimal qtyOrdered;
        private BigDecimal qtyReceived;
        /** 本单在途 = 订购−已收(非在途状态为 0) */
        private BigDecimal inTransitQty;
        /** 超期黄灯:预计到货日已过仍未收完 */
        private boolean overdue;
    }
}
