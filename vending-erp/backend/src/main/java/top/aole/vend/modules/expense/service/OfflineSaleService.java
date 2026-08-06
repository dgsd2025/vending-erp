package top.aole.vend.modules.expense.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.expense.dto.ExpenseDtos;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.domain.enums.CashFlowCategory;
import top.aole.vend.modules.money.domain.event.MoneyPostingEvent;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stock.mapper.SaleRecordMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 线下收入复合单(P2-13 穿行场景5:机器故障,顾客微信直转老板线下卖)。
 *
 * 一次录入(机器/SKU/数量/金额/收款账户)→ 同事务三件套原子落地:
 * ① sale_record:order_type=线下补录 + OFFLINE- 前缀单号 → 不入销售额/待结算口径
 *    (结算对账口径=仅正常,CostEngine/结算单预生成都不认线下补录),settlement_id 恒空;
 * ② cash_flow:MoneyPostingEvent(线下复合单)→ 类别「线下收入」→ pl_line 其他收入-平台外;
 * ③ 豁免标记:sale_record.offline_flag=true——机器推算账不含这次出货(后台没记录),
 *    下次盘点第 1 步先查账会亮"近 7 天有线下补录"提示,对应差异行勾「线下豁免」不算损耗。
 *
 * 任何一件失败(如账户不存在)整体回滚,不会出现"钱进了账、货账没豁免"半截单。
 */
@Service
@RequiredArgsConstructor
public class OfflineSaleService {

    public static final String REF_DOC_TYPE = "线下复合单";
    public static final String ORDER_TYPE_OFFLINE = "线下补录";
    public static final String ORDER_NO_PREFIX = "OFFLINE-";
    /** 冲销行单号前缀:OFFLINE-RF-<原 sale_record ID>(order_no 唯一键天然防双冲销) */
    public static final String REVERSE_PREFIX = "OFFLINE-RF-";

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SaleRecordMapper saleRecordMapper;
    private final MachineMapper machineMapper;
    private final ProductMapper productMapper;
    private final CashFlowMapper cashFlowMapper;
    private final PeriodLockService periodLockService;
    private final ApplicationEventPublisher publisher;
    private final OpLogService opLogService;

    @Transactional(rollbackFor = Exception.class)
    public ExpenseDtos.OfflineSaleResp create(ExpenseDtos.OfflineSaleReq req, Long userId, String operator) {
        if (req.getQty() == null || req.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("数量必须为正数:" + req.getQty());
        }
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("实收金额必须为正数:" + req.getAmount());
        }
        Machine machine = machineMapper.selectById(req.getMachineId());
        if (machine == null) {
            throw new BizException("机器不存在:id=" + req.getMachineId());
        }
        Product product = productMapper.selectById(req.getProductId());
        if (product == null) {
            throw new BizException("商品不存在:id=" + req.getProductId());
        }
        LocalDateTime bizTime = req.getBizTime() == null ? LocalDateTime.now() : req.getBizTime();
        String bizPeriod = bizTime.format(PERIOD);

        // ① sale_record(线下补录:不入销售额/待结算,不扣机器账——机器账后台权威)
        SaleRecord sale = new SaleRecord();
        sale.setOrderNo(ORDER_NO_PREFIX + IdUtil.getSnowflakeNextIdStr());
        sale.setMachineId(machine.getId());
        sale.setDeviceId(machine.getDeviceId());
        sale.setProductId(product.getId());
        sale.setQty(req.getQty());
        sale.setAmountReceived(req.getAmount());
        sale.setUnitPrice(req.getAmount().divide(req.getQty(), 4, RoundingMode.HALF_UP));
        sale.setPayMethod("线下直收");
        sale.setOrderType(ORDER_TYPE_OFFLINE);
        sale.setBizTime(bizTime);
        sale.setBizPeriod(bizPeriod);
        // 入账月与流水同口径(P0-2:业务月已锁 → 记当月)
        sale.setBookPeriod(periodLockService.isLocked(bizPeriod)
                ? YearMonth.now().format(PERIOD) : bizPeriod);
        // ③ 豁免标记:盘点第 1 步查账提示 + 差异行「线下豁免」的数据源
        sale.setOfflineFlag(true);
        sale.setSettlementId(null); // 不入待结算(口径守卫再兜一层)
        sale.setCreateUser(userId);
        saleRecordMapper.insert(sale);

        // ② cash_flow:其他收入-平台外(唯一合法通道=单据事件,失败整体回滚)
        MoneyPostingEvent event = new MoneyPostingEvent(REF_DOC_TYPE, sale.getId(), userId);
        event.inflow(req.getAccountId(), req.getAmount(), CashFlowCategory.OFFLINE_INCOME, bizTime,
                String.format("线下直收 %s×%s(%s)%s", product.getProductName(),
                        stripZeros(req.getQty()), machine.getMachineName(),
                        req.getRemark() == null ? "" : " " + req.getRemark()));
        publisher.publishEvent(event);

        CashFlow flow = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, sale.getId())
                .orderByDesc(CashFlow::getId).last("LIMIT 1"));
        if (flow == null) {
            throw new BizException("线下复合单过账后未查到流水(不应发生),整体回滚");
        }
        opLogService.record(operator, "线下收入复合单", "sale_record", sale.getId(), null,
                String.format("{\"orderNo\":\"%s\",\"amount\":%s,\"cashFlowId\":%d}",
                        sale.getOrderNo(), req.getAmount(), flow.getId()));

        ExpenseDtos.OfflineSaleResp resp = new ExpenseDtos.OfflineSaleResp();
        resp.setSaleRecordId(sale.getId());
        resp.setOrderNo(sale.getOrderNo());
        resp.setCashFlowId(flow.getId());
        resp.setExemptHint(String.format(
                "已标线下豁免通道:该机下次盘点若少 %s 件「%s」,差异行勾「线下豁免」不算损耗",
                stripZeros(req.getQty()), product.getProductName()));
        return resp;
    }

    // ============================== 一键冲销(M3-9 七律修复 P1-1d) ==============================

    /**
     * 线下复合单一键冲销(三件套整体反向,红字承接不删历史):
     * ① sale_record 冲销行:OFFLINE-RF-<原ID> 负数量/负金额(仍 线下补录+offline_flag,
     *    不入销售额/待结算口径;豁免提示与原单自然对冲);
     * ② 反向流水:线下收入「支」一笔退出原收款账户;
     * ③ 原单不改写(order_no 唯一键 OFFLINE-RF-<原ID> 天然防双冲销)。
     * 老板守卫(X-User-Role,同红冲/资金调整一把尺子)+ 备注强制。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExpenseDtos.OfflineSaleResp reverse(Long saleRecordId, String note, String role,
                                               Long userId, String operator) {
        periodLockService.assertBossRole(role, "线下复合单冲销");
        if (note == null || note.trim().isEmpty()) {
            throw new BizException("冲销线下复合单必须填写原因备注(留痕)");
        }
        SaleRecord origin = saleRecordMapper.selectById(saleRecordId);
        if (origin == null || !ORDER_TYPE_OFFLINE.equals(origin.getOrderType())) {
            throw new BizException("线下复合单不存在:id=" + saleRecordId);
        }
        if (origin.getOrderNo() != null && origin.getOrderNo().startsWith(REVERSE_PREFIX)) {
            throw new BizException(String.format("[%s]本身是冲销行,不能再冲销", origin.getOrderNo()));
        }
        String reverseNo = REVERSE_PREFIX + saleRecordId;
        Long existed = saleRecordMapper.selectCount(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getOrderNo, reverseNo));
        if (existed != null && existed > 0) {
            throw new BizException(String.format("[%s]已被冲销过,一张复合单只能冲销一次", origin.getOrderNo()));
        }
        // 找到原单流水(取收款账户;凭它把钱按原路退出去)
        CashFlow originFlow = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, saleRecordId)
                .eq(CashFlow::getDirection, CashFlow.DIR_IN)
                .orderByAsc(CashFlow::getId).last("LIMIT 1"));
        if (originFlow == null) {
            throw new BizException(String.format("[%s]没有找到原收款流水,不能冲销(数据异常请人工核对)", origin.getOrderNo()));
        }
        LocalDateTime now = LocalDateTime.now();
        String bizPeriod = now.format(PERIOD);

        // ① sale_record 冲销行(负量负额;order_no 唯一键防双冲销,并发后到者撞键回滚)
        SaleRecord rev = new SaleRecord();
        rev.setOrderNo(reverseNo);
        rev.setMachineId(origin.getMachineId());
        rev.setDeviceId(origin.getDeviceId());
        rev.setProductId(origin.getProductId());
        rev.setQty(origin.getQty().negate());
        rev.setAmountReceived(origin.getAmountReceived().negate());
        rev.setUnitPrice(origin.getUnitPrice());
        rev.setPayMethod("线下直收");
        rev.setOrderType(ORDER_TYPE_OFFLINE);
        rev.setBizTime(now);
        rev.setBizPeriod(bizPeriod);
        rev.setBookPeriod(periodLockService.isLocked(bizPeriod)
                ? YearMonth.now().format(PERIOD) : bizPeriod);
        rev.setOfflineFlag(true);
        rev.setSettlementId(null);
        rev.setCreateUser(userId);
        saleRecordMapper.insert(rev);

        // ② 反向流水:线下收入「支」退出原收款账户(同事务,写手校验失败整体回滚)
        MoneyPostingEvent event = new MoneyPostingEvent(REF_DOC_TYPE, rev.getId(), userId);
        event.outflow(originFlow.getAccountId(), origin.getAmountReceived(), CashFlowCategory.OFFLINE_INCOME,
                now, String.format("冲销线下复合单 %s。原因:%s", origin.getOrderNo(), note.trim()));
        publisher.publishEvent(event);

        CashFlow flow = cashFlowMapper.selectOne(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getRefDocType, REF_DOC_TYPE)
                .eq(CashFlow::getRefDocId, rev.getId())
                .orderByDesc(CashFlow::getId).last("LIMIT 1"));
        if (flow == null) {
            throw new BizException("冲销过账后未查到反向流水(不应发生),整体回滚");
        }
        opLogService.record(operator, "冲销线下复合单", "sale_record", saleRecordId, origin.getOrderNo(),
                String.format("{\"reverseOrderNo\":\"%s\",\"amount\":-%s,\"cashFlowId\":%d,\"note\":\"%s\"}",
                        reverseNo, origin.getAmountReceived(), flow.getId(), note.trim()));

        ExpenseDtos.OfflineSaleResp resp = new ExpenseDtos.OfflineSaleResp();
        resp.setSaleRecordId(rev.getId());
        resp.setOrderNo(reverseNo);
        resp.setCashFlowId(flow.getId());
        resp.setExemptHint("三件套已整体反向:销售记录红冲行 + 反向流水已落;盘点豁免提示与原单自然对冲");
        return resp;
    }

    /** 近期线下补录列表(复合单面板展示;标注冲销行/已被冲销) */
    public List<ExpenseDtos.OfflineSaleRow> listRecent(int limit) {
        List<SaleRecord> records = saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getOrderType, ORDER_TYPE_OFFLINE)
                .orderByDesc(SaleRecord::getId)
                .last("LIMIT " + (limit <= 0 ? 20 : limit)));
        // 一次查出这批原单的冲销行(OFFLINE-RF-<id>),标 reversed
        List<String> reverseNos = new ArrayList<>();
        for (SaleRecord s : records) {
            if (s.getOrderNo() != null && !s.getOrderNo().startsWith(REVERSE_PREFIX)) {
                reverseNos.add(REVERSE_PREFIX + s.getId());
            }
        }
        java.util.Set<String> existingReverse = new java.util.HashSet<>();
        if (!reverseNos.isEmpty()) {
            for (SaleRecord r : saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                    .in(SaleRecord::getOrderNo, reverseNos))) {
                existingReverse.add(r.getOrderNo());
            }
        }
        List<ExpenseDtos.OfflineSaleRow> rows = new ArrayList<>();
        for (SaleRecord s : records) {
            ExpenseDtos.OfflineSaleRow row = new ExpenseDtos.OfflineSaleRow();
            row.setSaleRecordId(s.getId());
            row.setOrderNo(s.getOrderNo());
            row.setMachineId(s.getMachineId());
            row.setProductId(s.getProductId());
            row.setQty(s.getQty());
            row.setAmount(s.getAmountReceived());
            row.setBizTime(s.getBizTime());
            boolean reversal = s.getOrderNo() != null && s.getOrderNo().startsWith(REVERSE_PREFIX);
            row.setReversal(reversal);
            row.setReversed(!reversal && existingReverse.contains(REVERSE_PREFIX + s.getId()));
            rows.add(row);
        }
        return rows;
    }

    private static String stripZeros(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
