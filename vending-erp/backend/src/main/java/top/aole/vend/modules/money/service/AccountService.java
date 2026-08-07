package top.aole.vend.modules.money.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.domain.enums.AccountType;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.mapper.CashFlowMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 资金账户 CRUD(M3-1):真实 4 类 + 虚拟 2 类。
 *
 * 口径:余额 = 期初 + Σ流水,实时推算不落表(附录D);
 * 期初余额**只能设一次**(条件更新防并发双设),再改必须走"资金调整单"(M3-5);
 * 有流水的账户永不删,只停用(同供应商档案纪律)。
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountMapper accountMapper;
    private final CashFlowMapper cashFlowMapper;
    private final OpLogService opLogService;

    @Transactional(rollbackFor = Exception.class)
    public Long create(MoneyDtos.AccountCreateReq req, Long userId, String operator) {
        AccountType type = AccountType.ofLabel(req.getAccountType().trim());
        Account exist = accountMapper.selectOne(new LambdaQueryWrapper<Account>()
                .eq(Account::getAccountName, req.getAccountName().trim()).last("LIMIT 1"));
        if (exist != null) {
            throw new BizException("账户名「" + req.getAccountName() + "」已存在");
        }
        Account account = new Account();
        account.setAccountName(req.getAccountName().trim());
        account.setAccountType(type.getLabel());
        account.setIsVirtual(type.isVirtual());
        account.setOpeningBalance(BigDecimal.ZERO);
        account.setCreateUser(userId);
        accountMapper.insert(account);
        opLogService.record(operator, "新建账户", "account", account.getId(), null, account);
        // 建户即设期初:走同一条一次性通道(锁定 opening_set_at)
        if (req.getOpeningBalance() != null) {
            setOpening(account.getId(), req.getOpeningBalance(), userId, operator);
        }
        return account.getId();
    }

    /**
     * 期初余额设置:仅一次。opening_set_at 非空即锁定,再调必抛——
     * 改动出口只有"资金调整单"(M3-5,doc_type=资金调整 → MoneyPostingEvent → cash_flow)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void setOpening(Long id, BigDecimal opening, Long userId, String operator) {
        Account account = mustGet(id);
        if (opening == null) {
            throw new BizException("期初余额不能为空");
        }
        int updated = accountMapper.setOpeningOnce(id, opening, userId);
        if (updated == 0) {
            throw new BizException("账户「" + account.getAccountName()
                    + "」期初余额只能设一次(已于 " + account.getOpeningSetAt()
                    + " 设定为 " + account.getOpeningBalance()
                    + ");如需修正请走「资金调整单」留痕(M3-5)");
        }
        opLogService.record(operator, "设置期初余额", "account", id,
                account.getOpeningBalance(), opening);
    }

    /** 改名(仅账户名;类型/期初不可改——类型错了停用重建,期初走调整单) */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, MoneyDtos.AccountUpdateReq req, Long userId, String operator) {
        Account account = mustGet(id);
        String before = account.getAccountName();
        account.setAccountName(req.getAccountName().trim());
        account.setUpdateUser(userId);
        accountMapper.updateById(account);
        opLogService.record(operator, "账户改名", "account", id, before, account.getAccountName());
    }

    /** 启用/停用(有流水永不删,只停用;停用后不能再走流水,历史保留) */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer targetStatus, Long userId, String operator) {
        if (targetStatus == null || (targetStatus != 0 && targetStatus != 1)) {
            throw new BizException("目标状态只能是 1(启用)/0(停用)");
        }
        Account account = mustGet(id);
        Integer before = account.getStatus();
        account.setStatus(targetStatus);
        account.setUpdateUser(userId);
        accountMapper.updateById(account);
        opLogService.record(operator, targetStatus == 1 ? "启用账户" : "停用账户",
                "account", id, before, targetStatus);
    }

    /** 余额 = 期初 + Σ流水(实时推算) */
    public BigDecimal balanceOf(Long id) {
        Account account = mustGet(id);
        BigDecimal opening = account.getOpeningBalance() == null ? BigDecimal.ZERO : account.getOpeningBalance();
        return opening.add(cashFlowMapper.sumNetByAccount(id));
    }

    /** 账户列表(带实时余额;含停用,前端置灰) */
    public List<MoneyDtos.AccountRow> list() {
        List<Account> accounts = accountMapper.selectList(new LambdaQueryWrapper<Account>()
                .orderByAsc(Account::getIsVirtual).orderByAsc(Account::getId));
        Map<Long, BigDecimal> netMap = new HashMap<>();
        if (!accounts.isEmpty()) {
            List<Long> ids = accounts.stream().map(Account::getId).collect(Collectors.toList());
            for (Map<String, Object> row : cashFlowMapper.sumNetBatch(ids)) {
                netMap.put(((Number) row.get("accountId")).longValue(),
                        new BigDecimal(row.get("net").toString()));
            }
        }
        List<MoneyDtos.AccountRow> rows = new ArrayList<>();
        for (Account a : accounts) {
            MoneyDtos.AccountRow row = new MoneyDtos.AccountRow();
            row.setId(a.getId());
            row.setAccountName(a.getAccountName());
            row.setAccountType(a.getAccountType());
            row.setIsVirtual(a.getIsVirtual());
            row.setOpeningBalance(a.getOpeningBalance());
            row.setOpeningSetAt(a.getOpeningSetAt());
            row.setOpeningLocked(a.getOpeningSetAt() != null);
            BigDecimal opening = a.getOpeningBalance() == null ? BigDecimal.ZERO : a.getOpeningBalance();
            row.setBalance(opening.add(netMap.getOrDefault(a.getId(), BigDecimal.ZERO)));
            row.setStatus(a.getStatus());
            rows.add(row);
        }
        return rows;
    }

    Account mustGet(Long id) {
        Account account = accountMapper.selectById(id);
        if (account == null) {
            throw new BizException("资金账户不存在:id=" + id);
        }
        return account;
    }

    /** 名称兜底查询(测试/内部用) */
    public Account byName(String name) {
        return accountMapper.selectOne(new LambdaQueryWrapper<Account>()
                .eq(Account::getAccountName, StrUtil.trim(name)).last("LIMIT 1"));
    }
}
