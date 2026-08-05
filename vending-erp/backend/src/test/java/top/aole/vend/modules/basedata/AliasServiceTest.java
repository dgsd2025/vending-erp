package top.aole.vend.modules.basedata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.AliasService;
import top.aole.vend.modules.basedata.application.ProductService;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SKU 别名 service 真库单测:绑定/改绑/解绑/待绑定队列确认。
 * 测试数据前缀 TSTA-,@AfterEach 物理清理。
 */
@SpringBootTest
class AliasServiceTest {

    private static final String P = "TSTA-";

    @Autowired
    private AliasService aliasService;
    @Autowired
    private ProductService productService;
    @Autowired
    private JdbcTemplate jdbc;

    private Product productA;
    private Product productB;

    @BeforeEach
    void setUp() {
        productA = createProduct(P + "A");
        productB = createProduct(P + "B");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM yc_vend_sku_alias WHERE alias_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM yc_vend_alias_pending WHERE alias_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM yc_vend_op_log WHERE target_type IN ('sku_alias','alias_pending') AND user_name='单测'");
        jdbc.update("DELETE ol FROM yc_vend_op_log ol JOIN yc_vend_product p ON ol.target_id = p.id AND ol.target_type='product' WHERE p.sku_code LIKE ?", P + "%");
        jdbc.update("DELETE pl FROM yc_vend_price_log pl JOIN yc_vend_product p ON pl.product_id = p.id WHERE p.sku_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM yc_vend_product WHERE sku_code LIKE ?", P + "%");
    }

    private Product createProduct(String code) {
        Product p = new Product();
        p.setSkuCode(code);
        p.setProductName("别名测试商品-" + code);
        p.setUnit("瓶");
        p.setBoxSpec(BigDecimal.ONE);
        return productService.create(p, "单测");
    }

    private Dtos.BindAliasReq bindReq(String code, String barcode, Long productId) {
        Dtos.BindAliasReq req = new Dtos.BindAliasReq();
        req.setAliasCode(code);
        req.setAliasBarcode(barcode);
        req.setAliasName("后台名-" + code);
        req.setProductId(productId);
        return req;
    }

    @Test
    void bind_is_idempotent_and_rebind_leaves_trace() {
        SkuAlias first = aliasService.bind(bindReq(P + "C1", "690001", productA.getId()), "单测");
        assertNotNull(first.getId());

        // 同键同商品重复绑定 → 幂等,不新增
        SkuAlias again = aliasService.bind(bindReq(P + "C1", "690001", productA.getId()), "单测");
        assertEquals(first.getId(), again.getId());

        // 同键改绑另一商品 → 原行更新,op_log 有改绑记录
        SkuAlias rebound = aliasService.bind(bindReq(P + "C1", "690001", productB.getId()), "单测");
        assertEquals(first.getId(), rebound.getId());
        assertEquals(productB.getId(), rebound.getProductId());
        Integer rebindLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yc_vend_op_log WHERE target_type='sku_alias' AND target_id=? AND action='改绑别名'",
                Integer.class, first.getId());
        assertEquals(1, rebindLog);

        // 编号与条码都空 → 拒绝(不许按名称绑)
        assertThrows(BizException.class,
                () -> aliasService.bind(bindReq("", "", productA.getId()), "单测"));
    }

    @Test
    void unbind_deletes_row_but_keeps_op_log() {
        SkuAlias alias = aliasService.bind(bindReq(P + "C2", "", productA.getId()), "单测");
        aliasService.unbind(alias.getId(), "单测");

        Integer remain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yc_vend_sku_alias WHERE id=?", Integer.class, alias.getId());
        assertEquals(0, remain);
        Integer trace = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yc_vend_op_log WHERE target_type='sku_alias' AND target_id=? AND action='解绑别名' AND before_json IS NOT NULL",
                Integer.class, alias.getId());
        assertEquals(1, trace, "解绑必须在 op_log 留 before 痕");

        // 解绑后同键可重新绑定
        SkuAlias rebound = aliasService.bind(bindReq(P + "C2", "", productB.getId()), "单测");
        assertNotNull(rebound.getId());
    }

    @Test
    void pending_confirm_writes_alias_and_flips_status() {
        jdbc.update("INSERT INTO yc_vend_alias_pending(alias_code, alias_barcode, alias_name, hit_count, suggest_product_id, pending_status) VALUES (?,?,?,?,?,?)",
                P + "C3", "690003", "后台名-C3", 5, productA.getId(), "待绑定");
        Long pendingId = jdbc.queryForObject("SELECT id FROM yc_vend_alias_pending WHERE alias_code=?",
                Long.class, P + "C3");

        SkuAlias alias = aliasService.confirmPending(pendingId, productA.getId(), "单测");
        assertEquals(productA.getId(), alias.getProductId());
        assertEquals("AI建议采纳", alias.getBindSource(), "确认的是 AI 建议的商品 → 来源记 AI建议采纳");

        String status = jdbc.queryForObject("SELECT pending_status FROM yc_vend_alias_pending WHERE id=?",
                String.class, pendingId);
        assertEquals("已绑定", status);

        // 已绑定的不能重复确认
        assertThrows(BizException.class,
                () -> aliasService.confirmPending(pendingId, productB.getId(), "单测"));
    }
}
