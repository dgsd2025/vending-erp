package top.aole.vend.modules.stocktake;

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
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.DocCreateReq;
import top.aole.vend.modules.doc.dto.DocItemReq;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.stock.service.StockService;
import top.aole.vend.modules.stocktake.domain.entity.Stocktake;
import top.aole.vend.modules.stocktake.domain.entity.StocktakeItem;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.mapper.StocktakeMapper;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 盲审 P1-1 并发回归(真并发,CountDownLatch 双线程):
 * 盘点确认原是"先读状态、后干活、最后改状态"的 check-then-act,双开标签页/离线队列重放/
 * 网络重试可让两个请求同时读到"待确认"→ 盘亏单、配对退库单、锚点全套生成两遍,污染财务账。
 * 修法:confirm 开头条件 UPDATE 抢状态(WHERE st_status='待确认',同 M1 DocStatusGuard 模式),
 * 抢不到的直接报"已被处理"。
 *
 * 本类**故意不加 @Transactional**——事务回滚型测试里,别的线程看不到未提交数据,
 * 测不了真并发。用例自己造数、用 JdbcTemplate 物理清理(逻辑删会占着 uk_st_no/uk_doc_no)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test-stocktake")
class StocktakeConcurrencyTest {

    private static final Long OP = 9L;

    @Autowired
    private StocktakeService stocktakeService;
    @Autowired
    private StocktakeMapper stocktakeMapper;
    @Autowired
    private DocService docService;
    @Autowired
    private StockService stockService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private DocHeadMapper docHeadMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> docIds = new ArrayList<>();
    private final List<Long> stocktakeIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!docIds.isEmpty()) {
            String in = docIds.toString().replace("[", "(").replace("]", ")");
            jdbc.update("DELETE FROM yc_vend_stock_ledger WHERE doc_id IN " + in);
            jdbc.update("DELETE FROM yc_vend_doc_item WHERE doc_id IN " + in);
            jdbc.update("DELETE FROM yc_vend_doc_head WHERE id IN " + in);
            jdbc.update("DELETE FROM yc_vend_op_log WHERE target_type='doc_head' AND target_id IN " + in);
        }
        for (Long stId : stocktakeIds) {
            jdbc.update("DELETE FROM yc_vend_stocktake_item WHERE stocktake_id = ?", stId);
            jdbc.update("DELETE FROM yc_vend_stocktake WHERE id = ?", stId);
            jdbc.update("DELETE FROM yc_vend_op_log WHERE target_type='stocktake' AND target_id = ?", stId);
        }
        for (Long pid : productIds) {
            jdbc.update("DELETE FROM yc_vend_product WHERE id = ?", pid);
        }
        docIds.clear();
        stocktakeIds.clear();
        productIds.clear();
    }

    private Long product(String name) {
        Product p = new Product();
        p.setSkuCode("SC" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        p.setProductName(name);
        p.setUnit("件");
        p.setProductStatus("在售");
        productMapper.insert(p);
        productIds.add(p.getId());
        return p.getId();
    }

    /** 垫仓库底(成本 3.5,单线程确认自动提交) */
    private void stockWarehouse(Long productId, String qty) {
        DocCreateReq req = new DocCreateReq();
        req.setDocType(DocType.PURCHASE_IN);
        req.setBizDate(LocalDate.now());
        DocItemReq item = new DocItemReq();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal("3.5"));
        req.setItems(Collections.singletonList(item));
        Long id = docService.createDoc(req, OP);
        docIds.add(id);
        docService.submit(id, OP);
        docService.confirm(id, OP, false);
    }

    @Test
    @DisplayName("P1-1:两线程同时 confirm 同一张待确认盘点单 → 恰好一次成功,另一个明确报「已被处理」,盘亏单只生成一张")
    void concurrentConfirmGeneratesFinancialDocsOnlyOnce() throws Exception {
        Long pid = product("并发盘点-薯片");
        stockWarehouse(pid, "10");

        // 建盘点单:账面 10,实盘 8(差异 −2 × 3.5 = −7,不触 ¥50 老板阈值)→ 提交到「待确认」
        StocktakeDtos.CreateReq createReq = new StocktakeDtos.CreateReq();
        createReq.setScopeType(Stocktake.SCOPE_WAREHOUSE);
        createReq.setSourceTask("手动");
        Long stId = stocktakeService.create(createReq, OP);
        stocktakeIds.add(stId);
        StocktakeDtos.SaveItemsReq itemsReq = new StocktakeDtos.SaveItemsReq();
        StocktakeDtos.ItemReq row = new StocktakeDtos.ItemReq();
        row.setProductId(pid);
        row.setActualQty(new BigDecimal("8"));
        row.setDiffReason(StocktakeItem.REASON_EXPIRE);
        itemsReq.setRows(Collections.singletonList(row));
        stocktakeService.saveItems(stId, itemsReq, OP);
        stocktakeService.submit(stId, OP);

        // 双线程闸门对齐后同时 confirm
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();
        List<String> failMessages = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    gate.await();
                    stocktakeService.confirm(stId, OP, "员工", false, null);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failMessages.add(e.getMessage() == null ? e.toString() : e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        gate.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发确认超时");
        pool.shutdownNow();

        assertEquals(1, success.get(), "两次并发确认只允许一次成功");
        assertEquals(1, failMessages.size(), "另一次必须被抢占守卫拒绝");
        assertTrue(failMessages.get(0).contains("已被处理"),
                "第二个请求要明确报「已被处理」而不是闷头重做:" + failMessages.get(0));

        // 财务后果只发生一遍
        Stocktake st = stocktakeMapper.selectById(stId);
        assertEquals(Stocktake.ST_DONE, st.getStStatus(), "盘点单终态=已完成");
        if (st.getLossDocId() != null) {
            docIds.add(st.getLossDocId());
        }
        Long lossDocs = docHeadMapper.selectCount(new LambdaQueryWrapper<DocHead>()
                .eq(DocHead::getDocType, DocType.LOSS_OUT)
                .like(DocHead::getRemark, st.getStNo()));
        assertEquals(1L, lossDocs, "盘亏单必须恰好一张(双确认会翻倍造财务单据)");
        assertEquals(0, stockService.getWarehouseStock(pid).compareTo(new BigDecimal("8")),
                "仓库账修正为实盘 8(过账两遍会变 6)");

        // 事后再确认(串行)也要被拒——状态已不是待确认
        BizException again = org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> stocktakeService.confirm(stId, OP, "员工", false, null));
        assertTrue(again.getMessage().contains("请先提交再确认") || again.getMessage().contains("已被处理"),
                again.getMessage());
    }
}
