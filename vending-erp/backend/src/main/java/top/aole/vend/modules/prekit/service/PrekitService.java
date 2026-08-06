package top.aole.vend.modules.prekit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.prekit.domain.entity.PrekitTicket;
import top.aole.vend.modules.prekit.domain.entity.PrekitTicketItem;
import top.aole.vend.modules.prekit.dto.PrekitDtos;
import top.aole.vend.modules.prekit.mapper.PrekitQueryMapper;
import top.aole.vend.modules.prekit.mapper.PrekitTicketItemMapper;
import top.aole.vend.modules.prekit.mapper.PrekitTicketMapper;
import top.aole.vend.modules.replenish.domain.entity.ReplenishPlan;
import top.aole.vend.modules.replenish.mapper.ReplenishPlanMapper;
import top.aole.vend.modules.replenish.service.ReplenishEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-kit 配货单服务(M2-3,调研 §4.4-5 + 穿行审计 P2-12):
 * 补货建议(机器侧勾选行)→ 按机分组配货单 → 仓库装箱出发(已执行)→
 * 次日通道2导入转移单按 机器×SKU(±48h 窗口)自动核销 → 差量=带回 → 带回率。
 *
 * 状态联动:建议行 建议 →(生成)已生成配货单 →(执行)已执行;
 * 配货单 已生成 →(执行)已执行 →(核销)已核销/有差异;超窗未核销亮黄灯(计算字段)。
 * takeback_rate 是补货 PDCA 核心指标的唯一数据生产者(P2-12)。
 *
 * FEFO 提示级(M2-7):明细行带"仓库最早入库日"(stock_ledger 最早正向流水日推),
 * 行 tooltip"先出旧货";完整批次/效期管理记债不做。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrekitService {

    /** 配货单操作系统用户占位(SSO 接入后换真实 userId,同 ImportService.IMPORT_USER 方案) */
    private static final Long PREKIT_USER = 1L;

    private static final String STATUS_PLAN_TICKETED = "已生成配货单";
    private static final String STATUS_PLAN_EXECUTED = "已执行";

    private final PrekitTicketMapper ticketMapper;
    private final PrekitTicketItemMapper itemMapper;
    private final PrekitQueryMapper queryMapper;
    private final ReplenishPlanMapper planMapper;
    private final DocHeadMapper docHeadMapper;
    private final DocService docService;
    private final OpLogService opLogService;

    // ============================== 生成(p2 勾选行 → 按机分组) ==============================

    /**
     * 从补货建议机器侧勾选行生成配货单:按机分组,一机一单;行 = SKU × 带出量(可改,默认建议量)。
     * 建议行状态 建议 → 已生成配货单(非「建议」行直接拒绝,天然幂等:同一行不会进两张单)。
     * 同机器+同配货日已有「已生成」单 → 追加行(不重复开单)。
     */
    @Transactional(rollbackFor = Exception.class)
    public PrekitDtos.GenerateResp generate(PrekitDtos.GenerateReq req, String operator) {
        // 建议行装载 + 校验
        Map<Long, BigDecimal> qtyOverride = new HashMap<>();
        List<ReplenishPlan> plans = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (PrekitDtos.GenerateItem item : req.getItems()) {
            if (!seen.add(item.getPlanId())) {
                throw new BizException("建议行重复勾选:planId=" + item.getPlanId());
            }
            ReplenishPlan plan = planMapper.selectById(item.getPlanId());
            if (plan == null) {
                throw new BizException("补货建议不存在:id=" + item.getPlanId());
            }
            if (!ReplenishEngine.TYPE_MACHINE.equals(plan.getPlanType()) || plan.getMachineId() == null) {
                throw new BizException("仅机器侧建议可生成配货单(采购建议请走一键转订货单):id=" + plan.getId());
            }
            if (!ReplenishEngine.STATUS_PENDING.equals(plan.getPlanStatus())) {
                throw new BizException("建议行[" + plan.getId() + "]状态为「" + plan.getPlanStatus()
                        + "」,仅「建议」可生成配货单(已生成过的不重复进单)");
            }
            if (item.getQty() != null) {
                qtyOverride.put(plan.getId(), item.getQty());
            }
            plans.add(plan);
        }

        // 按 机器×配货日 分组
        Map<String, List<ReplenishPlan>> groups = new LinkedHashMap<>();
        for (ReplenishPlan plan : plans) {
            groups.computeIfAbsent(plan.getMachineId() + "|" + plan.getPlanDate(),
                    k -> new ArrayList<>()).add(plan);
        }

        PrekitDtos.GenerateResp resp = new PrekitDtos.GenerateResp();
        resp.setTicketIds(new ArrayList<>());
        int itemCount = 0;
        int newTickets = 0;
        for (List<ReplenishPlan> group : groups.values()) {
            ReplenishPlan first = group.get(0);
            // 同机器+同配货日已有「已生成」单 → 追加;否则开新单
            PrekitTicket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<PrekitTicket>()
                    .eq(PrekitTicket::getMachineId, first.getMachineId())
                    .eq(PrekitTicket::getPlanDate, first.getPlanDate())
                    .eq(PrekitTicket::getTicketStatus, PrekitTicket.STATUS_CREATED)
                    .last("LIMIT 1"));
            if (ticket == null) {
                ticket = new PrekitTicket();
                ticket.setTicketNo(nextTicketNo(first.getPlanDate()));
                ticket.setMachineId(first.getMachineId());
                ticket.setPlanDate(first.getPlanDate());
                ticket.setTicketStatus(PrekitTicket.STATUS_CREATED);
                ticket.setCreateUser(PREKIT_USER);
                ticketMapper.insert(ticket);
                newTickets++;
            }
            for (ReplenishPlan plan : group) {
                PrekitTicketItem item = new PrekitTicketItem();
                item.setTicketId(ticket.getId());
                item.setProductId(plan.getProductId());
                item.setPlanId(plan.getId());
                item.setQtyPlanned(qtyOverride.getOrDefault(plan.getId(), plan.getSuggestQty()));
                item.setCreateUser(PREKIT_USER);
                itemMapper.insert(item);
                itemCount++;

                plan.setPlanStatus(STATUS_PLAN_TICKETED);
                plan.setUpdateUser(PREKIT_USER);
                planMapper.updateById(plan);
            }
            opLogService.record(operator, "生成配货单", "prekit_ticket", ticket.getId(), null,
                    "机器[" + first.getMachineId() + "] " + group.size() + " 行 → " + ticket.getTicketNo());
            resp.getTicketIds().add(ticket.getId());
        }
        resp.setTicketCount(newTickets);
        resp.setItemCount(itemCount);
        return resp;
    }

    /** 改带出量:仅「已生成」的单可改(装箱出发后数字冻结,差异走核销) */
    @Transactional(rollbackFor = Exception.class)
    public void updateItemQty(Long itemId, BigDecimal qtyPlanned, String operator) {
        PrekitTicketItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException("配货单明细不存在:id=" + itemId);
        }
        PrekitTicket ticket = mustGet(item.getTicketId());
        if (!PrekitTicket.STATUS_CREATED.equals(ticket.getTicketStatus())) {
            throw new BizException("配货单[" + ticket.getTicketNo() + "]状态为「" + ticket.getTicketStatus()
                    + "」,仅「已生成」可改带出量");
        }
        BigDecimal before = item.getQtyPlanned();
        item.setQtyPlanned(qtyPlanned);
        item.setUpdateUser(PREKIT_USER);
        itemMapper.updateById(item);
        opLogService.record(operator, "改配货带出量", "prekit_ticket_item", itemId, before, qtyPlanned);
    }

    // ============================== 执行(仓库装箱出发) ==============================

    /** 标记已执行:已生成 → 已执行,记执行人/时间;回链建议行 → 已执行 */
    @Transactional(rollbackFor = Exception.class)
    public void execute(Long ticketId, String operator) {
        PrekitTicket ticket = mustGet(ticketId);
        if (!PrekitTicket.STATUS_CREATED.equals(ticket.getTicketStatus())) {
            throw new BizException("配货单[" + ticket.getTicketNo() + "]状态为「" + ticket.getTicketStatus()
                    + "」,仅「已生成」可标记执行");
        }
        ticket.setTicketStatus(PrekitTicket.STATUS_EXECUTED);
        ticket.setExecBy(PREKIT_USER);
        ticket.setExecAt(LocalDateTime.now());
        ticket.setUpdateUser(PREKIT_USER);
        ticketMapper.updateById(ticket);

        // 建议行联动:已生成配货单 → 已执行
        for (PrekitTicketItem item : itemsOf(ticketId)) {
            if (item.getPlanId() == null) {
                continue;
            }
            ReplenishPlan plan = planMapper.selectById(item.getPlanId());
            if (plan != null && STATUS_PLAN_TICKETED.equals(plan.getPlanStatus())) {
                plan.setPlanStatus(STATUS_PLAN_EXECUTED);
                plan.setUpdateUser(PREKIT_USER);
                planMapper.updateById(plan);
            }
        }
        opLogService.record(operator, "配货单执行", "prekit_ticket", ticketId,
                PrekitTicket.STATUS_CREATED, PrekitTicket.STATUS_EXECUTED + "(经手:" + operator + ")");
    }

    // ============================== 核销(导入通道2钩子) ==============================

    /**
     * 导入通道2完成后钩子(ImportService.processReplenish 尾部一行调用):
     * 取本批生成的出库上架转移单 → 按 机器×(±48h 业务日窗口) 找未核销配货单 → 逐张核销。
     * 与导入同事务:核销失败整体回滚,不会出现"导入成功但核销悬空"的中间态。
     *
     * @return 本次核销的配货单数
     */
    @Transactional(rollbackFor = Exception.class)
    public int verifyAfterImport(Long batchId, String operator) {
        List<DocHead> docs = docHeadMapper.selectList(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getImportBatchId, batchId)
                .eq(DocHead::getDocType, DocType.TRANSFER_OUT)
                .eq(DocHead::getDocStatus, DocStatus.CONFIRMED));
        if (docs.isEmpty()) {
            return 0;
        }
        // 候选配货单:同机器,配货日在导入单业务日 ±48h 窗口内,未核销
        Set<Long> candidateIds = new LinkedHashSet<>();
        for (DocHead doc : docs) {
            List<PrekitTicket> candidates = ticketMapper.selectList(new LambdaQueryWrapper<PrekitTicket>()
                    .eq(PrekitTicket::getMachineId, doc.getMachineId())
                    .in(PrekitTicket::getTicketStatus, PrekitTicket.STATUS_CREATED, PrekitTicket.STATUS_EXECUTED)
                    .between(PrekitTicket::getPlanDate,
                            doc.getBizDate().minusDays(PrekitTicket.VERIFY_WINDOW_DAYS),
                            doc.getBizDate().plusDays(PrekitTicket.VERIFY_WINDOW_DAYS)));
            for (PrekitTicket t : candidates) {
                candidateIds.add(t.getId());
            }
        }
        int verified = 0;
        for (Long ticketId : candidateIds) {
            if (verifyOne(ticketMapper.selectById(ticketId), operator)) {
                verified++;
            }
        }
        return verified;
    }

    /** 手动核销(修复口:超窗后补导/补绑重导等场景,扫窗口重算一次) */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> verifyManually(Long ticketId, String operator) {
        PrekitTicket ticket = mustGet(ticketId);
        if (PrekitTicket.STATUS_VERIFIED.equals(ticket.getTicketStatus())
                || PrekitTicket.STATUS_DIFF.equals(ticket.getTicketStatus())) {
            throw new BizException("配货单[" + ticket.getTicketNo() + "]已核销过(状态:" + ticket.getTicketStatus() + ")");
        }
        boolean matched = verifyOne(ticket, operator);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", matched);
        result.put("ticket", ticketMapper.selectById(ticketId));
        if (!matched) {
            result.put("message", "±48h 窗口内没有该机器的导入转移单,无法核销——请先在导入中心导后台《系统补货记录》");
        }
        return result;
    }

    /**
     * 单张核销:窗口 = [配货日−2, 配货日+2],取该机器窗口内全部导入转移单按 SKU 汇总实上架量;
     * 逐行 带回 = max(带出 − 上架, 0);带回率 = Σ带回/Σ带出;全配 → 已核销,有差 → 有差异。
     * 无可匹配转移单 → 返回 false 保持原状态(超窗黄灯由列表计算字段亮)。
     */
    private boolean verifyOne(PrekitTicket ticket, String operator) {
        if (ticket == null || PrekitTicket.STATUS_VERIFIED.equals(ticket.getTicketStatus())
                || PrekitTicket.STATUS_DIFF.equals(ticket.getTicketStatus())) {
            return false; // 已核销过:重复导入零副作用(幂等)
        }
        LocalDate from = ticket.getPlanDate().minusDays(PrekitTicket.VERIFY_WINDOW_DAYS);
        LocalDate to = ticket.getPlanDate().plusDays(PrekitTicket.VERIFY_WINDOW_DAYS);
        Map<Long, BigDecimal> loadedBySku = new HashMap<>();
        for (Map<String, Object> row : queryMapper.importedLoadedByMachine(ticket.getMachineId(), from, to)) {
            loadedBySku.put(((Number) row.get("productId")).longValue(),
                    new BigDecimal(row.get("loadedQty").toString()));
        }
        if (loadedBySku.isEmpty()) {
            return false;
        }

        BigDecimal totalPlanned = BigDecimal.ZERO;
        BigDecimal totalTakeback = BigDecimal.ZERO;
        for (PrekitTicketItem item : itemsOf(ticket.getId())) {
            BigDecimal loaded = loadedBySku.getOrDefault(item.getProductId(), BigDecimal.ZERO);
            BigDecimal takeback = item.getQtyPlanned().subtract(loaded).max(BigDecimal.ZERO);
            item.setQtyLoaded(loaded);
            item.setQtyTakeback(takeback);
            item.setUpdateUser(PREKIT_USER);
            itemMapper.updateById(item);
            totalPlanned = totalPlanned.add(item.getQtyPlanned());
            totalTakeback = totalTakeback.add(takeback);
        }
        BigDecimal rate = totalPlanned.signum() <= 0 ? BigDecimal.ZERO
                : totalTakeback.divide(totalPlanned, 4, RoundingMode.HALF_UP);
        String before = ticket.getTicketStatus();
        ticket.setTicketStatus(totalTakeback.signum() > 0
                ? PrekitTicket.STATUS_DIFF : PrekitTicket.STATUS_VERIFIED);
        ticket.setTakebackRate(rate);
        ticket.setVerifyDocId(queryMapper.firstImportDocId(ticket.getMachineId(), from, to));
        ticket.setVerifyAt(LocalDateTime.now());
        ticket.setUpdateUser(PREKIT_USER);
        ticketMapper.updateById(ticket);
        opLogService.record(operator, "配货单核销", "prekit_ticket", ticket.getId(), before,
                ticket.getTicketStatus() + " 带回率=" + rate + "(带出 " + trim(totalPlanned)
                        + " 带回 " + trim(totalTakeback) + ")");
        return true;
    }

    // ============================== 查询 ==============================

    /** 配货单列表:带机器名/行数/计划合计 + 超窗黄灯(未核销且过窗 = overdue) */
    public List<Map<String, Object>> listTickets(String status) {
        List<Map<String, Object>> rows = queryMapper.listTickets(status);
        LocalDate today = LocalDate.now();
        for (Map<String, Object> row : rows) {
            String st = String.valueOf(row.get("ticketStatus"));
            LocalDate planDate = LocalDate.parse(String.valueOf(row.get("planDate")));
            boolean pendingVerify = PrekitTicket.STATUS_CREATED.equals(st)
                    || PrekitTicket.STATUS_EXECUTED.equals(st);
            row.put("overdue", pendingVerify
                    && today.isAfter(planDate.plusDays(PrekitTicket.VERIFY_WINDOW_DAYS)));
        }
        return rows;
    }

    /** 配货单详情:头 + 明细(商品名/FEFO 仓库最早入库日,打印友好视图取数口) */
    public Map<String, Object> ticketDetail(Long ticketId) {
        PrekitTicket ticket = mustGet(ticketId);
        List<Map<String, Object>> items = queryMapper.listItems(ticketId);
        // FEFO 提示级:各 SKU 仓库最早入库日(先出旧货)
        Set<Long> productIds = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            productIds.add(((Number) item.get("productId")).longValue());
        }
        Map<Long, Object> fefo = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Map<String, Object> row : queryMapper.earliestInboundDates(productIds)) {
                fefo.put(((Number) row.get("productId")).longValue(), row.get("earliestInDate"));
            }
        }
        for (Map<String, Object> item : items) {
            item.put("fefoEarliestInDate", fefo.get(((Number) item.get("productId")).longValue()));
        }
        Map<String, Object> head = queryMapper.listTickets(null).stream()
                .filter(r -> ticketId.equals(((Number) r.get("id")).longValue()))
                .findFirst().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket", ticket);
        result.put("head", head);
        result.put("items", items);
        return result;
    }

    /** 转移单列表(p5 转移单卡:来源=导入/手工,预挂单/已冲抵状态可见) */
    public List<Map<String, Object>> listTransfers() {
        return queryMapper.listTransfers();
    }

    // ============================== 手工转移单(兜底) ==============================

    /**
     * 手工转移单录入(p5 兜底入口):建单→提交→确认一步到位。
     * 出库上架:确认后进「预挂单」只锁仓库侧,待次日后台补货记录导入自动冲抵,超 48h 转正(P0-4);
     * 退库:直接已确认过账(机器 − 仓库 +)。成本单价 = 全期加权(无采购史为空)。
     * P0-3 碰撞检查/负库存拦截在 DocService.confirm 内照常生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createManualTransfer(PrekitDtos.ManualTransferReq req, String operator) {
        DocType type;
        if (DocType.TRANSFER_OUT.getLabel().equals(req.getDocType())) {
            type = DocType.TRANSFER_OUT;
        } else if (DocType.RETURN_BACK.getLabel().equals(req.getDocType())) {
            type = DocType.RETURN_BACK;
        } else {
            throw new BizException("手工转移单类型仅支持:出库上架/退库,收到:" + req.getDocType());
        }
        DocCreateReq docReq = new DocCreateReq();
        docReq.setDocType(type);
        docReq.setBizDate(req.getBizDate());
        docReq.setMachineId(req.getMachineId());
        docReq.setRemark(req.getRemark());
        List<DocItemReq> items = new ArrayList<>();
        for (PrekitDtos.ManualTransferItem it : req.getItems()) {
            DocItemReq item = new DocItemReq();
            item.setProductId(it.getProductId());
            item.setQty(it.getQty());
            item.setSlotNo(it.getSlotNo());
            item.setUnitPrice(queryMapper.weightedCost(it.getProductId()));
            items.add(item);
        }
        docReq.setItems(items);
        // 公开建单通道:来源由服务端强制=手工(P1-5),出库上架确认后自动进预挂单(P0-4)
        Long docId = docService.createDoc(docReq, PREKIT_USER);
        docService.submit(docId, PREKIT_USER);
        docService.confirm(docId, PREKIT_USER, false, null);
        DocHead head = docHeadMapper.selectById(docId);
        opLogService.record(operator, "手工转移单录入", "doc_head", docId, null,
                head.getDocNo() + " → " + head.getDocStatus().getLabel());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("docNo", head.getDocNo());
        result.put("docStatus", head.getDocStatus().getLabel());
        return result;
    }

    // ============================== 内部 ==============================

    private PrekitTicket mustGet(Long ticketId) {
        PrekitTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BizException("配货单不存在:" + ticketId);
        }
        return ticket;
    }

    private List<PrekitTicketItem> itemsOf(Long ticketId) {
        return itemMapper.selectList(new LambdaQueryWrapper<PrekitTicketItem>()
                .eq(PrekitTicketItem::getTicketId, ticketId).orderByAsc(PrekitTicketItem::getId));
    }

    /** 配货单号:PH-yyyyMMdd-三位流水(PH=配货,避开单据号前缀 PK=盘亏) */
    private String nextTicketNo(LocalDate planDate) {
        String like = "PH-" + planDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Long count = ticketMapper.selectCount(new LambdaQueryWrapper<PrekitTicket>()
                .likeRight(PrekitTicket::getTicketNo, like));
        return like + String.format("%03d", count + 1);
    }

    private static String trim(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
