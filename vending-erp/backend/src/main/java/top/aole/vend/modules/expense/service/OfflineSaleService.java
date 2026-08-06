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

    /** 近期线下补录列表(复合单面板展示) */
    public List<ExpenseDtos.OfflineSaleRow> listRecent(int limit) {
        List<ExpenseDtos.OfflineSaleRow> rows = new ArrayList<>();
        for (SaleRecord s : saleRecordMapper.selectList(new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getOrderType, ORDER_TYPE_OFFLINE)
                .orderByDesc(SaleRecord::getId)
                .last("LIMIT " + (limit <= 0 ? 20 : limit)))) {
            ExpenseDtos.OfflineSaleRow row = new ExpenseDtos.OfflineSaleRow();
            row.setSaleRecordId(s.getId());
            row.setOrderNo(s.getOrderNo());
            row.setMachineId(s.getMachineId());
            row.setProductId(s.getProductId());
            row.setQty(s.getQty());
            row.setAmount(s.getAmountReceived());
            row.setBizTime(s.getBizTime());
            rows.add(row);
        }
        return rows;
    }

    private static String stripZeros(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
