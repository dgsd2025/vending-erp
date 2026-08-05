package top.aole.vend.modules.doc.service;

import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.entity.DocItem;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.CostAdjustReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.mapper.DocItemMapper;
import top.aole.vend.modules.doc.mapper.DocQueryMapper;
import top.aole.vend.modules.period.service.PeriodLockService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 成本调整单(M1-7,审计 P0-1 单价错场景 · 附录C 过账契约)。
 *
 * 口径(§13.2-1):单价错 → 成本调整单(doc_type=成本调整,red_flush_of 指向原采购入库单):
 * - **未售部分**:按附录C 向 stock_ledger 插 qty=0 / amount=Δ 的调整流水(只动金额,
 *   移动加权引擎 M1-6 时序遍历时单位成本随之变);
 * - **已售部分**:不追溯成本——金额记入单据备注 + pl_line='成本调整' 标记,
 *   M3 利润表"成本调整"行实装取数(cash_flow.pl_line 同族);
 * - 已售/未售切分:已售 = min(本行采购量, 原单业务时点后的销量[正常+兑换]),FIFO 近似。
 */
@Service
@RequiredArgsConstructor
public class CostAdjustService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DocService docService;
    private final DocHeadMapper docHeadMapper;
    private final DocItemMapper docItemMapper;
    private final DocQueryMapper docQueryMapper;
    private final OpLogService opLogService;
    private final PeriodLockService periodLockService;

    // ============================== 预览 ==============================

    /** 成本调整预览:逐行算 Δ单价 × 未售/已售切分,前端确认后才执行 */
    public Preview preview(Long originDocId, CostAdjustReq req) {
        DocHead origin = mustGetAdjustable(originDocId);
        Preview p = new Preview();
        p.setOriginDocId(origin.getId());
        p.setOriginDocNo(origin.getDocNo());

        Map<Long, DocItem> itemMap = docService.itemsOf(originDocId).stream()
                .collect(Collectors.toMap(DocItem::getId, i -> i));
        Map<Long, Map<String, Object>> nameMap = docQueryMapper.itemsWithProduct(originDocId).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("docItemId")).longValue(), m -> m));

        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal unsoldTotal = BigDecimal.ZERO;
        BigDecimal soldTotal = BigDecimal.ZERO;
        for (CostAdjustReq.Line reqLine : req.getLines()) {
            DocItem item = itemMap.get(reqLine.getDocItemId());
            if (item == null) {
                throw new BizException(String.format("明细行[%d]不属于原单[%s]", reqLine.getDocItemId(), origin.getDocNo()));
            }
            BigDecimal wrongPrice = item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice();
            BigDecimal delta = reqLine.getCorrectPrice().subtract(wrongPrice);
            if (delta.compareTo(BigDecimal.ZERO) == 0) {
                throw new BizException(String.format("明细行[%d]正确单价与原单价相同(%s),无需调整",
                        reqLine.getDocItemId(), wrongPrice.stripTrailingZeros().toPlainString()));
            }
            BigDecimal qty = item.getQty();
            BigDecimal soldSince = docQueryMapper.soldSince(
                    item.getProductId(), origin.getBizDate().atStartOfDay());
            BigDecimal sold = soldSince.min(qty).max(BigDecimal.ZERO);
            BigDecimal unsold = qty.subtract(sold);
            BigDecimal unsoldAdjust = unsold.multiply(delta);
            BigDecimal soldAdjust = sold.multiply(delta);

            Map<String, Object> line = new LinkedHashMap<>();
            Map<String, Object> named = nameMap.get(item.getId());
            line.put("docItemId", item.getId());
            line.put("productId", item.getProductId());
            line.put("skuCode", named == null ? null : named.get("skuCode"));
            line.put("productName", named == null ? null : named.get("productName"));
            line.put("qty", qty);
            line.put("wrongPrice", wrongPrice);
            line.put("correctPrice", reqLine.getCorrectPrice());
            line.put("deltaPerUnit", delta);
            line.put("unsoldQty", unsold);
            line.put("soldQty", sold);
            line.put("unsoldAdjustAmount", unsoldAdjust); // → stock_ledger qty=0/amount=Δ
            line.put("soldAdjustAmount", soldAdjust);     // → 备注 + pl_line,M3 利润表行
            lines.add(line);
            unsoldTotal = unsoldTotal.add(unsoldAdjust);
            soldTotal = soldTotal.add(soldAdjust);
        }
        p.setLines(lines);
        p.setUnsoldAdjustTotal(unsoldTotal);
        p.setSoldAdjustTotal(soldTotal);
        p.setNote("未售部分插 qty=0/amount=Δ 的库存调整流水(调存货金额);已售部分不追溯,"
                + "记入单据备注 + pl_line='成本调整',M3 利润表\"成本调整\"行承接");
        return p;
    }

    // ============================== 执行 ==============================

    /** 旧签名兼容(无角色头场景):bossOverride=true 时会因角色缺失被拒 */
    @Transactional(rollbackFor = Exception.class)
    public Long execute(Long originDocId, CostAdjustReq req, Long userId) {
        return execute(originDocId, req, userId, null);
    }

    /**
     * 生成成本调整单并确认过账(未售 Δ 落 ledger;已售 Δ 落备注+pl_line)。
     *
     * 锁账守卫(七律审计,与红冲同一把尺子):原单入账月已锁 → 拒绝;
     * 老板越权(bossOverride + X-User-Role 老板角色 + 强制备注)放行,调整单本身落当月。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long execute(Long originDocId, CostAdjustReq req, Long userId, String role) {
        Preview p = preview(originDocId, req);
        DocHead origin = mustGetAdjustable(originDocId);

        // P0-2 锁账守卫:锁账线之前的原单禁成本调整;老板越权先验角色头(P1-2 同逻辑)
        String bookPeriod = origin.getBookPeriod() != null
                ? origin.getBookPeriod() : origin.getBizDate().format(PERIOD);
        if (req.isBossOverride()) {
            periodLockService.assertBossRole(role, "锁账期成本调整越权");
        }
        periodLockService.assertPeriodOperable(bookPeriod, "成本调整", req.isBossOverride(), req.getRemark());

        DocHead head = new DocHead();
        head.setDocNo(docService.nextDocNo(DocType.COST_ADJUST, LocalDate.now()));
        head.setDocType(DocType.COST_ADJUST);
        head.setDocStatus(DocStatus.DRAFT);
        head.setBizDate(LocalDate.now()); // 调整落在当日 → book_period=当月,锁账月旧报表永不重算(P0-2)
        head.setSupplierId(origin.getSupplierId());
        head.setDocSource(DocService.SOURCE_MANUAL);
        head.setRedFlushOf(originDocId); // 复用"指向原单"语义:成本调整锚定原采购入库单
        head.setNegStockExempt(false);   // qty=0 不动数量,无负库存语义
        head.setTotalQty(BigDecimal.ZERO);
        head.setTotalAmount(p.getUnsoldAdjustTotal());
        head.setPlLine(p.getSoldAdjustTotal().compareTo(BigDecimal.ZERO) != 0 ? "成本调整" : null);
        StringBuilder remark = new StringBuilder(String.format(
                "成本调整原单[%s]:未售调存货 %+.2f;已售不追溯 %+.2f(→利润表\"成本调整\"行,M3)",
                origin.getDocNo(), p.getUnsoldAdjustTotal(), p.getSoldAdjustTotal()));
        if (req.getRemark() != null && !req.getRemark().trim().isEmpty()) {
            remark.append("。").append(req.getRemark().trim());
        }
        head.setRemark(remark.length() > 490 ? remark.substring(0, 490) : remark.toString());
        head.setCreateUser(userId);
        docHeadMapper.insert(head);

        for (Map<String, Object> line : p.getLines()) {
            DocItem item = new DocItem();
            item.setDocId(head.getId());
            item.setProductId(((Number) line.get("productId")).longValue());
            item.setQty(BigDecimal.ZERO);                              // 附录C:qty=0 只动金额
            item.setUnitPrice((BigDecimal) line.get("deltaPerUnit"));  // Δ单价留痕
            item.setAmount((BigDecimal) line.get("unsoldAdjustAmount")); // 未售Δ金额 → ledger
            item.setRemark(String.format("单价 %s→%s Δ%s;未售%s件调存货%+.2f;已售%s件%+.2f不追溯",
                    strip(line.get("wrongPrice")), strip(line.get("correctPrice")), strip(line.get("deltaPerUnit")),
                    strip(line.get("unsoldQty")), (BigDecimal) line.get("unsoldAdjustAmount"),
                    strip(line.get("soldQty")), (BigDecimal) line.get("soldAdjustAmount")));
            item.setCreateUser(userId);
            docItemMapper.insert(item);
        }
        opLogService.recordJson(userId, "新建成本调整单", "doc_head", head.getId(),
                null, JSONUtil.toJsonStr(head));

        docService.submit(head.getId(), userId);
        docService.confirm(head.getId(), userId, false, null); // 过账:listener 成本调整分支写 qty=0/amount=Δ 流水
        return head.getId();
    }

    /** 原单校验:仅已过账的采购入库单可做成本调整(单价错场景) */
    private DocHead mustGetAdjustable(Long originDocId) {
        DocHead origin = docHeadMapper.selectById(originDocId);
        if (origin == null) {
            throw new BizException("原单据不存在:" + originDocId);
        }
        if (origin.getDocType() != DocType.PURCHASE_IN) {
            throw new BizException(String.format("成本调整仅针对采购入库单(单价错场景),原单[%s]类型为[%s]",
                    origin.getDocNo(), origin.getDocType().getLabel()));
        }
        DocStatus st = origin.getDocStatus();
        if (st != DocStatus.CONFIRMED && st != DocStatus.PENDING_SETTLE
                && st != DocStatus.SETTLED && st != DocStatus.COMPLETED) {
            throw new BizException(String.format("原单[%s]状态为[%s],未过账/已红冲的单据不能做成本调整",
                    origin.getDocNo(), st.getLabel()));
        }
        return origin;
    }

    private static String strip(Object v) {
        return new BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString();
    }

    /** 成本调整预览(前端确认对话框数据) */
    @Data
    public static class Preview {
        private Long originDocId;
        private String originDocNo;
        private List<Map<String, Object>> lines;
        /** Σ未售调整额(将落 stock_ledger qty=0 行) */
        private BigDecimal unsoldAdjustTotal;
        /** Σ已售调整额(备注+pl_line,M3 利润表行) */
        private BigDecimal soldAdjustTotal;
        private String note;
    }
}
