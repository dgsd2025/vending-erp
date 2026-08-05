package top.aole.vend.modules.doc.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.doc.domain.DocStateMachine;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.domain.event.DocConfirmedEvent;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.mapper.DocItemMapper;
import top.aole.vend.modules.stock.service.StockService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 通用单据服务:doc_head + doc_item 的创建/查询/状态流转/确认过账。
 *
 * 硬规则:
 * 1. 单据不许删——本服务(及全系统)没有 delete 方法,只有红冲/作废两条逆向路。
 * 2. 草稿态可改,已确认(含之后所有状态)不可改,改则抛异常。
 * 3. 确认动作 = 记 confirm_by/confirm_at + 写 op_log + 同步派发 DocConfirmedEvent
 *    (库存引擎唯一写入触发源,过账失败整体回滚)。
 * 4. 手工出库上架单确认后 = 预挂单(P0-4):只锁仓库侧,等导入冲抵,超48h转正。
 * 5. 手工单确认前跑碰撞检查(P0-3):后台已有出货(导入转移单)禁止手工再录。
 */
@Service
@RequiredArgsConstructor
public class DocService {

    public static final String SOURCE_MANUAL = "手工";
    public static final String SOURCE_IMPORT = "导入";

    private final DocHeadMapper docHeadMapper;
    private final DocItemMapper docItemMapper;
    private final OpLogService opLogService;
    private final StockService stockService;
    private final ApplicationEventPublisher eventPublisher;

    // ============================== 创建 / 修改 ==============================

    /** 创建单据(草稿态) */
    @Transactional(rollbackFor = Exception.class)
    public Long createDoc(DocCreateReq req, Long userId) {
        if (req.getDocType().isTransfer() && req.getMachineId() == null) {
            throw new BizException("转移类单据(" + req.getDocType().getLabel() + ")必须关联机器");
        }
        if (req.getDocType() == DocType.RED_FLUSH && req.getRedFlushOf() == null) {
            throw new BizException("红冲单必须指向原单据(red_flush_of)");
        }
        DocHead head = new DocHead();
        head.setDocNo(generateDocNo(req.getDocType(), req.getBizDate()));
        head.setDocType(req.getDocType());
        head.setDocStatus(DocStatus.DRAFT);
        head.setBizDate(req.getBizDate());
        head.setMachineId(req.getMachineId());
        head.setSupplierId(req.getSupplierId());
        head.setPurchaseOrderId(req.getPurchaseOrderId());
        head.setDocSource(req.getDocSource() == null ? SOURCE_MANUAL : req.getDocSource());
        head.setImportBatchId(req.getImportBatchId());
        head.setRedFlushOf(req.getRedFlushOf());
        head.setNegStockExempt(req.getDocType().isNegExemptByDefault());
        head.setDueDate(req.getDueDate());
        head.setHandlerUser(req.getHandlerUser());
        head.setRemark(req.getRemark());
        head.setCreateUser(userId);
        fillTotals(head, req.getItems());
        docHeadMapper.insert(head);
        insertItems(head, req.getItems(), userId);
        opLogService.recordJson(userId, "新建", "doc_head", head.getId(), null, JSONUtil.toJsonStr(head));
        return head.getId();
    }

    /** 修改草稿:仅草稿态允许;已确认(及之后)一律抛异常 */
    @Transactional(rollbackFor = Exception.class)
    public void updateDraft(Long docId, DocCreateReq req, Long userId) {
        DocHead head = mustGet(docId);
        if (head.getDocStatus() != DocStatus.DRAFT) {
            throw new BizException(String.format("单据[%s]当前状态为[%s],仅草稿态可修改;已确认单据只能红冲",
                    head.getDocNo(), head.getDocStatus().getLabel()));
        }
        String before = JSONUtil.toJsonStr(head);
        head.setBizDate(req.getBizDate());
        head.setMachineId(req.getMachineId());
        head.setSupplierId(req.getSupplierId());
        head.setDueDate(req.getDueDate());
        head.setHandlerUser(req.getHandlerUser());
        head.setRemark(req.getRemark());
        head.setUpdateUser(userId);
        fillTotals(head, req.getItems());
        docHeadMapper.updateById(head);
        // 草稿改明细 = 整套替换(物理删旧行仅限草稿明细,单据头永不删)
        docItemMapper.delete(new LambdaQueryWrapper<DocItem>().eq(DocItem::getDocId, docId));
        insertItems(head, req.getItems(), userId);
        opLogService.recordJson(userId, "修改", "doc_head", docId, before, JSONUtil.toJsonStr(head));
    }

    // ============================== 状态流转 ==============================

    /** 提交:草稿 → 待确认 */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long docId, Long userId) {
        transition(mustGet(docId), DocStatus.PENDING_CONFIRM, userId, "提交");
    }

    /** 作废:仅草稿/待确认可作废(已过账单据只能红冲) */
    @Transactional(rollbackFor = Exception.class)
    public void voidDoc(Long docId, Long userId) {
        transition(mustGet(docId), DocStatus.VOID, userId, "作废");
    }

    /** 完成:已确认/已结算 → 已完成 */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long docId, Long userId) {
        transition(mustGet(docId), DocStatus.COMPLETED, userId, "完成");
    }

    /** 确认(默认无豁免、业务时间=biz_date 当日 00:00) */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long docId, Long userId, boolean exempt) {
        confirm(docId, userId, exempt, null);
    }

    /**
     * 确认单据:状态机校验 → 记确认人/时间 → 写 op_log → 派发过账事件(同事务)。
     *
     * @param exempt  负库存拦截豁免(P1-8):仅 导入来源/红冲/期初 允许;手工单请求豁免直接拒绝
     * @param bizTime 库存流水业务时间戳;导入通道(M1-3)传后台补货记录真实时间,空=biz_date 当日 00:00
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long docId, Long userId, boolean exempt, LocalDateTime bizTime) {
        DocHead head = mustGet(docId);
        DocType type = head.getDocType();

        // 豁免权限校验:手工单不豁免(P1-8)
        if (exempt && SOURCE_MANUAL.equals(head.getDocSource())
                && type != DocType.RED_FLUSH && type != DocType.OPENING) {
            throw new BizException("手工单据不允许豁免负库存拦截(仅导入通道/红冲/期初可豁免,P1-8)");
        }

        // P0-3 碰撞检查:手工转移单确认前,同机器+SKU+日期已有导入生成的出库上架单 → 拒绝
        if (type == DocType.TRANSFER_OUT && SOURCE_MANUAL.equals(head.getDocSource())) {
            List<DocItem> items = itemsOf(docId);
            for (DocItem item : items) {
                List<Long> conflicts = stockService.checkManualTransferCollision(
                        head.getMachineId(), item.getProductId(), head.getBizDate());
                if (!conflicts.isEmpty()) {
                    throw new BizException(String.format(
                            "后台已有出货记录,禁止手工再录出库(P0-3):机器[%d]商品[%d]日期[%s]已存在导入转移单%s",
                            head.getMachineId(), item.getProductId(), head.getBizDate(), conflicts));
                }
            }
        }

        // P0-4:手工出库上架单确认后进"预挂单"(只锁仓库侧);其余进"已确认"
        boolean prePending = type == DocType.TRANSFER_OUT && SOURCE_MANUAL.equals(head.getDocSource());
        DocStatus target = prePending ? DocStatus.PRE_PENDING : DocStatus.CONFIRMED;
        DocStateMachine.assertTransition(type, head.getDocStatus(), target);

        String before = JSONUtil.toJsonStr(head);
        head.setDocStatus(target);
        head.setConfirmBy(userId);
        head.setConfirmAt(LocalDateTime.now());
        if (exempt || type.isNegExemptByDefault()) {
            head.setNegStockExempt(true);
        }
        head.setUpdateUser(userId);
        docHeadMapper.updateById(head);
        opLogService.recordJson(userId, prePending ? "确认(预挂单)" : "确认", "doc_head",
                head.getId(), before, JSONUtil.toJsonStr(head));

        // 库存引擎唯一写入触发源:同步事件,过账失败(负库存拦截)整体回滚
        DocConfirmedEvent.PostingPhase phase = prePending
                ? DocConfirmedEvent.PostingPhase.WAREHOUSE_ONLY
                : DocConfirmedEvent.PostingPhase.FULL;
        eventPublisher.publishEvent(new DocConfirmedEvent(head, itemsOf(docId), phase,
                bizTime != null ? bizTime : head.getBizDate().atStartOfDay()));
    }

    /**
     * 预挂单转正(P0-4):超 48h 无后台补货记录的手工预挂单转"已确认",补写机器侧库存。
     * 仓库侧在预挂单时已锁定,此处只补机器侧(MACHINE_ONLY)。
     *
     * TODO(M1-3):加定时任务钩子——每小时扫描 confirm_at 超 48h 且 matched_doc_id 为空的
     *   预挂单,自动调本方法转正;导入通道匹配成功的走 StockService.matchPendingTransfer 冲抵。
     */
    @Transactional(rollbackFor = Exception.class)
    public void promotePendingTransfer(Long docId, Long userId, LocalDateTime bizTime) {
        DocHead head = mustGet(docId);
        if (head.getDocStatus() != DocStatus.PRE_PENDING) {
            throw new BizException(String.format("单据[%s]状态为[%s],不是预挂单,无法转正",
                    head.getDocNo(), head.getDocStatus().getLabel()));
        }
        DocStateMachine.assertTransition(head.getDocType(), head.getDocStatus(), DocStatus.CONFIRMED);
        String before = JSONUtil.toJsonStr(head);
        head.setDocStatus(DocStatus.CONFIRMED);
        head.setUpdateUser(userId);
        docHeadMapper.updateById(head);
        opLogService.recordJson(userId, "预挂单转正", "doc_head", docId, before, JSONUtil.toJsonStr(head));
        eventPublisher.publishEvent(new DocConfirmedEvent(head, itemsOf(docId),
                DocConfirmedEvent.PostingPhase.MACHINE_ONLY,
                bizTime != null ? bizTime : head.getBizDate().atStartOfDay()));
    }

    /**
     * 红冲入口(P0-1)——扩展点,连锁逻辑 M1-7 实现:
     * 生成 doc_type=红冲 的反向单(red_flush_of=原单)→ 影响清单确认页 → 确认后反向过账
     * (免负库存拦截)→ 原单流转"已红冲";已付款场景连锁 settle_bill 红字(M3)。
     */
    public Long redFlush(Long originDocId, Long userId, String reason) {
        // TODO(M1-7):红冲连锁——本票只留通道(red_flush_of 字段 + 状态机 + 免拦截豁免)
        throw new BizException("红冲连锁逻辑将在 M1-7 实现,当前仅保留通道(red_flush_of/状态机/豁免)");
    }

    // ============================== 查询 ==============================

    public DocDetail getDoc(Long docId) {
        DocDetail detail = new DocDetail();
        detail.setHead(mustGet(docId));
        detail.setItems(itemsOf(docId));
        return detail;
    }

    public List<DocHead> listDocs(DocType docType, DocStatus docStatus) {
        return docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(docType != null, DocHead::getDocType, docType)
                .eq(docStatus != null, DocHead::getDocStatus, docStatus)
                .orderByDesc(DocHead::getId));
    }

    public List<DocItem> itemsOf(Long docId) {
        return docItemMapper.selectList(new LambdaQueryWrapper<DocItem>()
                .eq(DocItem::getDocId, docId).orderByAsc(DocItem::getId));
    }

    // ============================== 内部 ==============================

    /** 通用状态流转(状态机校验 + op_log) */
    private void transition(DocHead head, DocStatus target, Long userId, String action) {
        DocStateMachine.assertTransition(head.getDocType(), head.getDocStatus(), target);
        String before = JSONUtil.toJsonStr(head);
        head.setDocStatus(target);
        head.setUpdateUser(userId);
        docHeadMapper.updateById(head);
        opLogService.recordJson(userId, action, "doc_head", head.getId(), before, JSONUtil.toJsonStr(head));
    }

    private DocHead mustGet(Long docId) {
        DocHead head = docHeadMapper.selectById(docId);
        if (head == null) {
            throw new BizException("单据不存在:" + docId);
        }
        return head;
    }

    private void fillTotals(DocHead head, List<DocItemReq> items) {
        head.setTotalQty(items.stream().map(DocItemReq::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        head.setTotalAmount(items.stream()
                .map(i -> i.getQty().multiply(i.getUnitPrice() == null ? BigDecimal.ZERO : i.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private void insertItems(DocHead head, List<DocItemReq> items, Long userId) {
        for (DocItemReq req : items) {
            DocItem item = new DocItem();
            item.setDocId(head.getId());
            item.setProductId(req.getProductId());
            item.setSlotNo(req.getSlotNo());
            item.setBoxQty(req.getBoxQty());
            item.setQty(req.getQty());
            item.setExpectQty(req.getExpectQty());
            item.setUnitPrice(req.getUnitPrice() == null ? BigDecimal.ZERO : req.getUnitPrice());
            item.setAmount(item.getQty().multiply(item.getUnitPrice()));
            item.setBatchNo(req.getBatchNo());
            item.setExpireDate(req.getExpireDate());
            item.setPoItemId(req.getPoItemId());
            item.setRemark(req.getRemark());
            item.setCreateUser(userId);
            docItemMapper.insert(item);
        }
    }

    /** 单据号:前缀-yyyyMMdd-三位流水,如 RK-20260806-001 */
    private String generateDocNo(DocType type, LocalDate bizDate) {
        String day = bizDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String like = type.getPrefix() + "-" + day + "-";
        Long count = docHeadMapper.selectCount(new LambdaQueryWrapper<DocHead>()
                .likeRight(DocHead::getDocNo, like));
        return like + String.format("%03d", count + 1);
    }

    /** 单据详情(头+明细) */
    @Data
    public static class DocDetail {
        private DocHead head;
        private List<DocItem> items;

        public List<Long> productIds() {
            return items.stream().map(DocItem::getProductId).collect(Collectors.toList());
        }
    }
}
