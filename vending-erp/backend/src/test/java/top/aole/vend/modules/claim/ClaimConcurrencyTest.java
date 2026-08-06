package top.aole.vend.modules.claim;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.modules.claim.domain.entity.Claim;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.mapper.ClaimMapper;
import top.aole.vend.modules.claim.service.ClaimService;
import top.aole.vend.modules.expense.domain.entity.Expense;
import top.aole.vend.modules.expense.dto.ExpenseDtos;
import top.aole.vend.modules.expense.mapper.EquipmentMapper;
import top.aole.vend.modules.expense.mapper.ExpenseMapper;
import top.aole.vend.modules.expense.service.ExpenseService;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-9 盲审 P0-2 并发回归(真并发,CountDownLatch 双线程):
 * 索赔到账 / 支出确认 原是 check-then-act——并发双确认会落两笔流水,
 * 其他收入-赔付 / 杂费 行直接翻倍污染利润表(支出还会插两行设备台账)。
 * 修法 = 条件更新抢占(申请中→确认中 / 待确认→确认中)。
 *
 * 本类故意不加 @Transactional(事务回滚型测试测不了真并发),自己造数、物理清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test-claim")
class ClaimConcurrencyTest {

    private static final Long OP = 9L;
    private static final String OPERATOR = "并发验收员";

    @Autowired
    private ClaimService claimService;
    @Autowired
    private ClaimMapper claimMapper;
    @Autowired
    private ExpenseService expenseService;
    @Autowired
    private ExpenseMapper expenseMapper;
    @Autowired
    private EquipmentMapper equipmentMapper;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> claimIds = new ArrayList<>();
    private final List<Long> expenseIds = new ArrayList<>();
    private final List<Long> accountIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : claimIds) {
            jdbc.update("DELETE FROM yc_vend_cash_flow WHERE ref_doc_type='索赔单' AND ref_doc_id = ?", id);
            jdbc.update("DELETE FROM yc_vend_attachment WHERE ref_type='claim' AND ref_id = ?", id);
            jdbc.update("DELETE FROM yc_vend_claim WHERE id = ?", id);
        }
        for (Long id : expenseIds) {
            jdbc.update("DELETE FROM yc_vend_cash_flow WHERE ref_doc_type='支出单' AND ref_doc_id = ?", id);
            jdbc.update("DELETE FROM yc_vend_attachment WHERE ref_type='expense' AND ref_id = ?", id);
            jdbc.update("DELETE FROM yc_vend_equipment WHERE expense_id = ?", id);
            jdbc.update("DELETE FROM yc_vend_expense WHERE id = ?", id);
        }
        for (Long id : accountIds) {
            jdbc.update("DELETE FROM yc_vend_account WHERE id = ?", id);
        }
        claimIds.clear();
        expenseIds.clear();
        accountIds.clear();
    }

    private Account account(String opening) {
        Account a = new Account();
        a.setAccountName("CC账户" + IdUtil.fastSimpleUUID().substring(0, 8));
        a.setAccountType("微信");
        a.setIsVirtual(false);
        a.setOpeningBalance(new BigDecimal(opening));
        a.setOpeningSetAt(LocalDateTime.now());
        accountMapper.insert(a);
        accountIds.add(a.getId());
        return a;
    }

    /** 双线程闸门对齐后同时跑同一动作,返回 [成功数, 失败消息列表] */
    private Object[] race(Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();
        List<String> fails = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    gate.await();
                    action.run();
                    success.incrementAndGet();
                } catch (Exception e) {
                    fails.add(e.getMessage() == null ? e.toString() : e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        gate.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发执行超时");
        pool.shutdownNow();
        return new Object[]{success.get(), fails};
    }

    @Test
    @DisplayName("P0-2:两线程同时登记同一张索赔到账 → 恰 1 成功;CLAIM_INCOME 流水恰 1 笔(其他收入-赔付不翻倍),余额只加一次")
    @SuppressWarnings("unchecked")
    void concurrentClaimReceivePostsExactlyOnce() throws Exception {
        Account a = account("100");
        ClaimDtos.CreateReq createReq = new ClaimDtos.CreateReq();
        createReq.setClaimTarget(Claim.TARGET_FACTORY);
        createReq.setAmount(new BigDecimal("88.80"));
        Long claimId = claimService.create(createReq, OP, OPERATOR);
        claimIds.add(claimId);
        attachmentService.upload("claim", claimId, "赔付凭证", "赔付.png",
                "png".getBytes(StandardCharsets.UTF_8), OP, OPERATOR);

        ClaimDtos.ReceiveReq receiveReq = new ClaimDtos.ReceiveReq();
        receiveReq.setAccountId(a.getId());
        Object[] result = race(() -> claimService.receive(claimId, receiveReq, OP, OPERATOR));
        int success = (Integer) result[0];
        List<String> fails = (List<String>) result[1];

        assertEquals(1, success, "并发双登记只许一次成功");
        assertEquals(1, fails.size());
        assertTrue(fails.get(0).contains("已被他人处理") || fails.get(0).contains("仅申请中"),
                "后到者人话报错:" + fails.get(0));
        assertEquals(1, cashFlowMapper.selectCount(new LambdaQueryWrapper<CashFlow>()
                        .eq(CashFlow::getRefDocType, "索赔单").eq(CashFlow::getRefDocId, claimId)).intValue(),
                "CLAIM_INCOME 流水恰 1 笔");
        assertEquals(0, accountService.balanceOf(a.getId()).compareTo(new BigDecimal("188.80")),
                "余额 100+88.8 只加一次");
        assertEquals(Claim.STATUS_RECEIVED, claimMapper.selectById(claimId).getClaimStatus());
    }

    @Test
    @DisplayName("P0-2:两线程同时确认同一张支出单 → 恰 1 成功;支出流水恰 1 笔(杂费不翻倍)、设备台账恰 1 行")
    @SuppressWarnings("unchecked")
    void concurrentExpenseConfirmPostsExactlyOnce() throws Exception {
        Account a = account("500");
        ExpenseDtos.ExpenseCreateReq createReq = new ExpenseDtos.ExpenseCreateReq();
        createReq.setCategory(Expense.CATEGORY_EQUIPMENT);
        createReq.setAmount(new BigDecimal("120"));
        createReq.setAccountId(a.getId());
        createReq.setEquipName("并发测试货架");
        Long expId = expenseService.create(createReq, OP, OPERATOR);
        expenseIds.add(expId);
        attachmentService.upload("expense", expId, "发票", "发票.png",
                "png".getBytes(StandardCharsets.UTF_8), OP, OPERATOR);

        Object[] result = race(() -> expenseService.confirm(expId, OP, OPERATOR));
        int success = (Integer) result[0];
        List<String> fails = (List<String>) result[1];

        assertEquals(1, success, "并发双确认只许一次成功");
        assertEquals(1, fails.size());
        assertTrue(fails.get(0).contains("已被他人处理") || fails.get(0).contains("仅待确认"),
                "后到者人话报错:" + fails.get(0));
        assertEquals(1, cashFlowMapper.selectCount(new LambdaQueryWrapper<CashFlow>()
                        .eq(CashFlow::getRefDocType, "支出单").eq(CashFlow::getRefDocId, expId)).intValue(),
                "支出流水恰 1 笔");
        assertEquals(0, accountService.balanceOf(a.getId()).compareTo(new BigDecimal("380")),
                "余额 500−120 只扣一次");
        assertEquals(1, equipmentMapper.selectCount(
                        new LambdaQueryWrapper<top.aole.vend.modules.expense.domain.entity.Equipment>()
                                .eq(top.aole.vend.modules.expense.domain.entity.Equipment::getExpenseId, expId)).intValue(),
                "设备台账恰 1 行(双确认会插两行)");
        assertEquals(Expense.STATUS_DONE, expenseMapper.selectById(expId).getExpStatus());
    }
}
