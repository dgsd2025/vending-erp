package top.aole.vend.modules.settle;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.entity.CashFlow;
import top.aole.vend.modules.money.mapper.AccountMapper;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.settle.domain.entity.Deduction;
import top.aole.vend.modules.settle.domain.entity.Payment;
import top.aole.vend.modules.settle.domain.entity.SettleBill;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.DeductionMapper;
import top.aole.vend.modules.settle.mapper.PaymentMapper;
import top.aole.vend.modules.settle.mapper.SettleBillMapper;
import top.aole.vend.modules.settle.service.DeductionService;
import top.aole.vend.modules.settle.service.PaymentService;
import top.aole.vend.modules.settle.service.SettleBillService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
 * M3-9 盲审 P0-1 / P1-8 并发回归(真并发,CountDownLatch 双线程):
 *
 * - P0-1 付款确认原是 check-then-act:双开页面/两人同时确认 → cash_flow 落两笔"供应商付款",
 *   账户余额被双扣。修法 = 条件更新抢占(待付款→确认中,仿 SettlementService)。
 * - P1-8 结算单复核对抵扣先 selectById 再判"待抵扣":两张结算单并发带入同一张抵扣 →
 *   供应商少收两次钱。修法 = 抵扣占用条件更新(WHERE 待抵扣)+ 结算单 待确认→待付款 条件更新。
 *
 * 本类**故意不加 @Transactional**(事务回滚型测试里别的线程看不到未提交数据,测不了真并发),
 * 用例自己造数、JdbcTemplate 物理清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test-settle")
class SettleConcurrencyTest {

    private static final Long OP = 9L;
    private static final String OPERATOR = "并发验收员";
    private static final String ROLE_BOSS = "老板";

    @Autowired
    private DocService docService;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private SettleBillMapper billMapper;
    @Autowired
    private PaymentMapper paymentMapper;
    @Autowired
    private DeductionMapper deductionMapper;
    @Autowired
    private SettleBillService settleBillService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private DeductionService deductionService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> supplierIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> accountIds = new ArrayList<>();
    private final List<Long> docIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long sid : supplierIds) {
            List<Long> payIds = jdbc.queryForList(
                    "SELECT id FROM yc_vend_payment WHERE supplier_id = ?", Long.class, sid);
            for (Long payId : payIds) {
                jdbc.update("DELETE FROM yc_vend_cash_flow WHERE ref_doc_type='付款单' AND ref_doc_id = ?", payId);
                jdbc.update("DELETE FROM yc_vend_attachment WHERE ref_type='payment' AND ref_id = ?", payId);
            }
            jdbc.update("DELETE FROM yc_vend_payment WHERE supplier_id = ?", sid);
            jdbc.update("DELETE FROM yc_vend_deduction WHERE supplier_id = ?", sid);
            jdbc.update("DELETE FROM yc_vend_settle_bill WHERE supplier_id = ?", sid);
            jdbc.update("DELETE FROM yc_vend_supplier WHERE id = ?", sid);
        }
        if (!docIds.isEmpty()) {
            String in = docIds.toString().replace("[", "(").replace("]", ")");
            jdbc.update("DELETE FROM yc_vend_stock_ledger WHERE doc_id IN " + in);
            jdbc.update("DELETE FROM yc_vend_doc_item WHERE doc_id IN " + in);
            jdbc.update("DELETE FROM yc_vend_doc_head WHERE id IN " + in);
        }
        for (Long pid : productIds) {
            jdbc.update("DELETE FROM yc_vend_product WHERE id = ?", pid);
        }
        for (Long aid : accountIds) {
            jdbc.update("DELETE FROM yc_vend_account WHERE id = ?", aid);
        }
        supplierIds.clear();
        productIds.clear();
        accountIds.clear();
        docIds.clear();
    }

    // ============================== 造数 ==============================

    private Supplier supplier(String name) {
        Supplier s = new Supplier();
        s.setSupplierCode("CC" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        s.setSupplierName(name);
        s.setSettleMethod("现结");
        s.setOpeningPayable(BigDecimal.ZERO);
        s.setCoopStatus("合作中");
        supplierMapper.insert(s);
        supplierIds.add(s.getId());
        return s;
    }

    private Product product(String name) {
        Product p = new Product();
        p.setSkuCode("CCP" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        p.setProductName(name);
        p.setUnit("瓶");
        p.setProductStatus("在售");
        productMapper.insert(p);
        productIds.add(p.getId());
        return p;
    }

    private Account account(String opening) {
        Account a = new Account();
        a.setAccountName("CC微信" + IdUtil.fastSimpleUUID().substring(0, 8));
        a.setAccountType("微信");
        a.setIsVirtual(false);
        a.setOpeningBalance(new BigDecimal(opening));
        a.setOpeningSetAt(LocalDateTime.now());
        accountMapper.insert(a);
        accountIds.add(a.getId());
        return a;
    }

    private Long purchase(Long supplierId, Long productId, String qty, String price) {
        DocCreateReq r = new DocCreateReq();
        r.setDocType(DocType.PURCHASE_IN);
        r.setBizDate(LocalDate.now());
        r.setSupplierId(supplierId);
        DocItemReq item = new DocItemReq();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        r.setItems(Collections.singletonList(item));
        Long id = docService.createDoc(r, OP);
        docIds.add(id);
        docService.submit(id, OP);
        docService.confirm(id, OP, false, null);
        return id;
    }

    private SettleBill billOf(Long docId) {
        return billMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getSourceDocId, docId)
                .eq(SettleBill::getDirection, SettleBill.DIR_NORMAL).last("LIMIT 1"));
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

    // ============================== 用例 ==============================

    @Test
    @DisplayName("P0-1:两线程同时确认同一张付款单 → 恰 1 成功,另一个报「已被他人处理」;cash_flow 恰 1 笔,余额只扣一次")
    @SuppressWarnings("unchecked")
    void concurrentPaymentConfirmPostsExactlyOnce() throws Exception {
        Supplier s = supplier("并发付款供应商");
        Product p = product("并发付款商品");
        Account a = account("5000");
        Long docId = purchase(s.getId(), p.getId(), "100", "3.5"); // 350
        SettleBill bill = billOf(docId);
        settleBillService.confirm(bill.getId(), null, OP, OPERATOR, ROLE_BOSS);

        SettleDtos.PaymentCreateReq req = new SettleDtos.PaymentCreateReq();
        req.setSupplierId(s.getId());
        req.setAccountId(a.getId());
        req.setAmount(new BigDecimal("350"));
        req.setSettleBillId(bill.getId());
        Long payId = paymentService.create(req, OP, OPERATOR);
        attachmentService.upload("payment", payId, "转账截图", "转账.png",
                "png".getBytes(StandardCharsets.UTF_8), OP, OPERATOR);

        Object[] result = race(() -> paymentService.confirm(payId, OP, OPERATOR));
        int success = (Integer) result[0];
        List<String> fails = (List<String>) result[1];

        assertEquals(1, success, "两次并发确认只许一次成功");
        assertEquals(1, fails.size());
        assertTrue(fails.get(0).contains("已被他人处理") || fails.get(0).contains("仅[待付款]"),
                "后到者人话报错:" + fails.get(0));
        assertEquals(1, cashFlowMapper.selectCount(new LambdaQueryWrapper<CashFlow>()
                        .eq(CashFlow::getRefDocType, "付款单").eq(CashFlow::getRefDocId, payId)).intValue(),
                "流水恰 1 笔(双确认会双扣账户)");
        assertEquals(0, accountService.balanceOf(a.getId()).compareTo(new BigDecimal("4650")),
                "余额 5000−350,只扣一次");
        assertEquals(Payment.ST_SETTLED, paymentMapper.selectById(payId).getPayStatus());
        assertEquals(SettleBill.ST_DONE, billMapper.selectById(bill.getId()).getBillStatus());
    }

    @Test
    @DisplayName("P1-8:同一张待抵扣被两张结算单并发带入 → 恰 1 张占到,另一张整单回滚;抵扣只锚定一张单")
    @SuppressWarnings("unchecked")
    void concurrentBillConfirmCannotDoubleUseDeduction() throws Exception {
        Supplier s = supplier("并发抵扣供应商");
        Product p = product("并发抵扣商品");
        Long doc1 = purchase(s.getId(), p.getId(), "100", "3.5"); // 350
        Long doc2 = purchase(s.getId(), p.getId(), "100", "3.5"); // 350
        SettleBill bill1 = billOf(doc1);
        SettleBill bill2 = billOf(doc2);

        SettleDtos.DeductionCreateReq dedReq = new SettleDtos.DeductionCreateReq();
        dedReq.setSupplierId(s.getId());
        dedReq.setDedSource("兑换");
        dedReq.setAmount(new BigDecimal("50"));
        dedReq.setPeriodDesc("并发双用测试");
        Long dedId = deductionService.create(dedReq, OP, OPERATOR);

        List<Long> bills = new CopyOnWriteArrayList<>();
        bills.add(bill1.getId());
        bills.add(bill2.getId());
        Object[] result = race(() -> {
            Long billId = bills.remove(0); // 两线程各拿一张单,带同一张抵扣
            settleBillService.confirm(billId, Collections.singletonList(dedId), OP, OPERATOR, ROLE_BOSS);
        });
        int success = (Integer) result[0];
        List<String> fails = (List<String>) result[1];

        assertEquals(1, success, "同一张抵扣只许被一张结算单占到");
        assertEquals(1, fails.size());
        assertTrue(fails.get(0).contains("已被他人带入") || fails.get(0).contains("仅[待抵扣]"),
                "后到者人话报错:" + fails.get(0));

        Deduction ded = deductionMapper.selectById(dedId);
        assertEquals(Deduction.ST_USED, ded.getDedStatus());
        SettleBill winner = billMapper.selectById(ded.getUsedSettleBillId());
        assertEquals(0, winner.getAmountActual().compareTo(new BigDecimal("300.00")),
                "占到的那张:实结=350−50");
        // 另一张整单回滚回待确认原样(应结=实结=350,可重新复核)
        Long loserId = winner.getId().equals(bill1.getId()) ? bill2.getId() : bill1.getId();
        SettleBill loser = billMapper.selectById(loserId);
        assertEquals(SettleBill.ST_PENDING_CONFIRM, loser.getBillStatus(), "失败方整单回滚,原样待确认");
        assertEquals(0, loser.getAmountActual().compareTo(new BigDecimal("350.00")),
                "失败方没有偷偷把抵扣算进实结(供应商不能少收两次钱)");
    }
}
