package top.aole.vend.modules.doc;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;
import top.aole.vend.modules.stock.service.StockService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盲审 P0-A / P1-3 并发回归(真并发,CountDownLatch 双线程):
 *
 * 本类**故意不加 @Transactional**——事务回滚型测试里,别的线程看不到未提交数据,
 * 测不了真并发。每个用例自己造数、用 JdbcTemplate 物理清理(逻辑删会占着 uk_doc_no)。
 *
 * ① P0-A:两线程同时 confirm 同一张单 → 条件更新(WHERE doc_status=旧)保证只过账一次;
 * ② P1-3:库存10,两张各出8的手工单并发确认 → SKU 行锁(SELECT FOR UPDATE)把
 *    "查余额+写流水"串行化,只放行一单,仓库不被透支成负数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DocConcurrencyTest {

    private static final Long OP = 9L;

    @Autowired
    private DocService docService;
    @Autowired
    private StockService stockService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private StockLedgerMapper stockLedgerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> docIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // 物理清理(非逻辑删):逻辑删的 doc_head 会占着 uk_doc_no,污染后续单号生成
        if (!docIds.isEmpty()) {
            String in = docIds.toString().replace("[", "(").replace("]", ")");
            jdbc.update("DELETE FROM yc_vend_stock_ledger WHERE doc_id IN " + in);
            jdbc.update("DELETE FROM yc_vend_doc_item WHERE doc_id IN " + in);
            jdbc.update("DELETE FROM yc_vend_doc_head WHERE id IN " + in);
            jdbc.update("DELETE FROM yc_vend_op_log WHERE target_type='doc_head' AND target_id IN " + in);
        }
        for (Long pid : productIds) {
            jdbc.update("DELETE FROM yc_vend_product WHERE id = ?", pid);
        }
        docIds.clear();
        productIds.clear();
    }

    private Long product(String name) {
        Product p = new Product();
        p.setSkuCode("CC" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        p.setProductName(name);
        p.setUnit("瓶");
        p.setProductStatus("在售");
        productMapper.insert(p);
        productIds.add(p.getId());
        return p.getId();
    }

    private Long draftDoc(DocType type, Long machineId, Long productId, String qty, String price) {
        DocCreateReq req = new DocCreateReq();
        req.setDocType(type);
        req.setMachineId(machineId);
        req.setBizDate(LocalDate.now());
        DocItemReq item = new DocItemReq();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        req.setItems(Collections.singletonList(item));
        Long id = docService.createDoc(req, OP);
        docIds.add(id);
        docService.submit(id, OP);
        return id;
    }

    /** 双线程同时执行两个动作(闸门对齐后同时放行),返回 [成功数, 失败数] */
    private int[] race(Runnable a, Runnable b) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        for (Runnable task : new Runnable[]{a, b}) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    gate.await();
                    task.run();
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        gate.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务超时");
        pool.shutdownNow();
        return new int[]{success.get(), failure.get()};
    }

    @Test
    @DisplayName("P0-A:两线程同时 confirm 同一张采购入库单 → 只过账一次(条件更新拦下第二人)")
    void concurrentConfirmSameDocPostsOnlyOnce() throws Exception {
        Long pid = product("并发测试-可乐");
        Long docId = draftDoc(DocType.PURCHASE_IN, null, pid, "5", "3.0");

        int[] r = race(
                () -> docService.confirm(docId, OP, false),
                () -> docService.confirm(docId, OP, false));

        assertEquals(1, r[0], "两次并发确认只允许一次成功");
        assertEquals(1, r[1], "另一次必须抛异常(已被他人处理/非法流转)");

        DocHead head = docHeadMapper.selectById(docId);
        assertEquals(DocStatus.CONFIRMED, head.getDocStatus());
        // 账只记一遍:该单仓库流水恰好 1 条,库存 = 5 而不是 10
        Long ledgerRows = stockLedgerMapper.selectCount(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getDocId, docId));
        assertEquals(1L, ledgerRows, "库存流水必须只有一条(双过账=P0 账错)");
        assertEquals(0, stockService.getWarehouseStock(pid).compareTo(new BigDecimal("5")),
                "库存必须是 5(过账两次会变 10)");
    }

    @Test
    @DisplayName("P1-3:库存10,两张各出8的手工单并发确认 → SKU 行锁串行化,只放行一单,不透支")
    void concurrentOutboundCannotOverdraw() throws Exception {
        Long pid = product("并发测试-矿泉水");
        // 垫底库存 10(单线程确认,自动提交)
        Long poId = draftDoc(DocType.PURCHASE_IN, null, pid, "10", "2.0");
        docService.confirm(poId, OP, false);
        assertEquals(0, stockService.getWarehouseStock(pid).compareTo(new BigDecimal("10")));

        // 两张不同的手工出库上架单(同 SKU 各出 8):确认后=预挂单,只锁仓库侧(-8)
        Long out1 = draftDoc(DocType.TRANSFER_OUT, 990001L, pid, "8", "2.0");
        Long out2 = draftDoc(DocType.TRANSFER_OUT, 990002L, pid, "8", "2.0");

        int[] r = race(
                () -> docService.confirm(out1, OP, false),
                () -> docService.confirm(out2, OP, false));

        assertEquals(1, r[0], "库存只够一单出,必须恰好一单成功");
        assertEquals(1, r[1], "另一单必须被负库存拦截(修前两单都放行 → -6)");
        assertEquals(0, stockService.getWarehouseStock(pid).compareTo(new BigDecimal("2")),
                "仓库结存必须是 10-8=2,绝不允许透支为负");
        // 失败那单必须停在待确认(整个事务回滚,状态没被推进)
        long confirmed = 0;
        long pendingConfirm = 0;
        for (Long id : new Long[]{out1, out2}) {
            DocStatus st = docHeadMapper.selectById(id).getDocStatus();
            if (st == DocStatus.PRE_PENDING) {
                confirmed++;
            } else if (st == DocStatus.PENDING_CONFIRM) {
                pendingConfirm++;
            }
        }
        assertEquals(1, confirmed, "成功单=预挂单(手工转移单口径)");
        assertEquals(1, pendingConfirm, "被拦截单必须回到待确认(事务整体回滚)");
    }
}
