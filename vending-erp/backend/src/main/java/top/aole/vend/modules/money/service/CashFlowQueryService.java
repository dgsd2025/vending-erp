package top.aole.vend.modules.money.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.mapper.CashFlowMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金流水只读接口(M3-1):分页(账户/类别/期间筛选)+ pl_line×月聚合(利润表取数口)。
 * 只读——写入只有 CashFlowWriter(包私有,事件驱动)一条路。
 */
@Service
@RequiredArgsConstructor
public class CashFlowQueryService {

    private final CashFlowMapper cashFlowMapper;
    private final AccountMapper accountMapper;

    /** 流水分页:accountId/category/period(book_period 闭区间)可选 */
    public Page<MoneyDtos.FlowRow> page(long current, long size, Long accountId,
                                        String category, String fromPeriod, String toPeriod) {
        Page<CashFlow> page = cashFlowMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<CashFlow>()
                        .eq(accountId != null, CashFlow::getAccountId, accountId)
                        .eq(StrUtil.isNotBlank(category), CashFlow::getCategory, category)
                        .ge(StrUtil.isNotBlank(fromPeriod), CashFlow::getBookPeriod, fromPeriod)
                        .le(StrUtil.isNotBlank(toPeriod), CashFlow::getBookPeriod, toPeriod)
                        .orderByDesc(CashFlow::getBizTime).orderByDesc(CashFlow::getId));

        Map<Long, String> nameMap = new HashMap<>();
        for (Account a : accountMapper.selectList(null)) {
            nameMap.put(a.getId(), a.getAccountName());
        }
        Page<MoneyDtos.FlowRow> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<MoneyDtos.FlowRow> rows = new ArrayList<>();
        for (CashFlow f : page.getRecords()) {
            MoneyDtos.FlowRow row = new MoneyDtos.FlowRow();
            row.setId(f.getId());
            row.setFlowNo(f.getFlowNo());
            row.setAccountId(f.getAccountId());
            row.setAccountName(nameMap.get(f.getAccountId()));
            row.setDirection(f.getDirection());
            row.setAmount(f.getAmount());
            row.setCategory(f.getCategory());
            row.setPlLine(f.getPlLine());
            row.setRefDocType(f.getRefDocType());
            row.setRefDocId(f.getRefDocId());
            row.setBizTime(f.getBizTime());
            row.setBookPeriod(f.getBookPeriod());
            row.setRemark(f.getRemark());
            rows.add(row);
        }
        result.setRecords(rows);
        return result;
    }

    /** 利润表取数口:pl_line × 入账月 聚合(M3 报表票直接用) */
    public List<MoneyDtos.PlSummaryRow> plMonthlySummary(String fromPeriod, String toPeriod) {
        List<MoneyDtos.PlSummaryRow> rows = new ArrayList<>();
        for (Map<String, Object> raw : cashFlowMapper.plMonthlySummary(
                StrUtil.blankToDefault(fromPeriod, null), StrUtil.blankToDefault(toPeriod, null))) {
            MoneyDtos.PlSummaryRow row = new MoneyDtos.PlSummaryRow();
            row.setPlLine((String) raw.get("plLine"));
            row.setPeriod((String) raw.get("period"));
            row.setInflow(new BigDecimal(raw.get("inflow").toString()));
            row.setOutflow(new BigDecimal(raw.get("outflow").toString()));
            row.setNet(row.getInflow().subtract(row.getOutflow()));
            rows.add(row);
        }
        return rows;
    }
}
