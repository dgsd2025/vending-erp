package top.aole.vend.modules.basedata.application;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.PriceLog;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.PriceLogMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 商品档案应用服务。
 * 铁律:停售≠删除,有流水的永不删,只改 product_status;售价修改额外写 price_log;所有写操作记 op_log。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    /** 商品三态 */
    public static final List<String> PRODUCT_STATUSES = Arrays.asList("在售", "清仓中", "停售");

    private final ProductMapper productMapper;
    private final PriceLogMapper priceLogMapper;
    private final OpLogService opLogService;

    /** 分页列表:名称/编码/条码 关键字 + 分类 + 状态 筛选 */
    public Page<Product> page(long current, long size, String keyword, String category, String productStatus) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            qw.and(w -> w.like(Product::getProductName, keyword)
                    .or().like(Product::getSkuCode, keyword)
                    .or().like(Product::getBarcode, keyword));
        }
        qw.eq(StrUtil.isNotBlank(category), Product::getCategory, category)
                .eq(StrUtil.isNotBlank(productStatus), Product::getProductStatus, productStatus)
                .orderByAsc(Product::getSkuCode);
        return productMapper.selectPage(new Page<>(current, size), qw);
    }

    public Product getById(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BizException("商品不存在:id=" + id);
        }
        return product;
    }

    @Transactional(rollbackFor = Exception.class)
    public Product create(Product product, String operator) {
        if (StrUtil.isBlank(product.getSkuCode()) || StrUtil.isBlank(product.getProductName())) {
            throw new BizException("SKU 编码与商品名不能为空");
        }
        Long dup = productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getSkuCode, product.getSkuCode()));
        if (dup != null && dup > 0) {
            throw new BizException("SKU 编码已存在:" + product.getSkuCode());
        }
        if (StrUtil.isBlank(product.getProductStatus())) {
            product.setProductStatus("在售");
        }
        product.setId(null);
        productMapper.insert(product);
        opLogService.record(operator, "新建", "product", product.getId(), null, product);
        if (product.getRefPrice() != null) {
            insertPriceLog(product.getId(), null, product.getRefPrice());
        }
        return product;
    }

    /** 编辑:refPrice 变化时额外写 price_log(手工来源) */
    @Transactional(rollbackFor = Exception.class)
    public Product update(Long id, Product incoming, String operator) {
        Product before = getById(id);
        if (StrUtil.isNotBlank(incoming.getSkuCode()) && !incoming.getSkuCode().equals(before.getSkuCode())) {
            throw new BizException("SKU 编码不允许修改(需要拆分请走一码多品清洗流程)");
        }
        incoming.setId(id);
        incoming.setSkuCode(before.getSkuCode());
        // 状态不从编辑口改,走 changeStatus,防止绕过清仓/停售规则
        incoming.setProductStatus(before.getProductStatus());
        productMapper.updateById(incoming);
        Product after = productMapper.selectById(id);
        opLogService.record(operator, "修改", "product", id, before, after);
        if (priceChanged(before.getRefPrice(), after.getRefPrice())) {
            insertPriceLog(id, before.getRefPrice(), after.getRefPrice());
        }
        return after;
    }

    /**
     * 状态流转:在售/清仓中/停售(停售≠删除)。
     * 清仓中 → 记 clearance_since;恢复在售 → 清空 clearance_since。
     */
    @Transactional(rollbackFor = Exception.class)
    public Product changeStatus(Long id, String targetStatus, String operator) {
        if (!PRODUCT_STATUSES.contains(targetStatus)) {
            throw new BizException("非法商品状态:" + targetStatus + ",只允许 在售/清仓中/停售");
        }
        Product before = getById(id);
        if (targetStatus.equals(before.getProductStatus())) {
            return before;
        }
        LambdaUpdateWrapper<Product> uw = new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, id)
                .set(Product::getProductStatus, targetStatus)
                .set(Product::getClearanceSince, "清仓中".equals(targetStatus) ? LocalDate.now() : null);
        productMapper.update(null, uw);
        Product after = productMapper.selectById(id);
        opLogService.record(operator, "改状态", "product", id, before, after);
        return after;
    }

    /**
     * 采购守卫(P2-10/穿行场景9):清仓中商品禁采购——订货单与采购入库两个入口共用。
     * 只拦档案里明确是"清仓中"的商品;档案不存在的 ID 不拦(期初裸数据/测试数据不受影响)。
     */
    public void assertPurchasable(java.util.Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        List<Product> blocked = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .in(Product::getId, productIds)
                .eq(Product::getProductStatus, "清仓中"));
        if (!blocked.isEmpty()) {
            String names = blocked.stream()
                    .map(p -> p.getSkuCode() + " " + p.getProductName())
                    .collect(java.util.stream.Collectors.joining("、"));
            throw new BizException("清仓中商品禁止采购(P2-10):" + names
                    + "。如需恢复进货,请先在商品档案改回\"在售\"");
        }
    }

    private boolean priceChanged(BigDecimal oldPrice, BigDecimal newPrice) {
        if (newPrice == null) {
            return false;
        }
        return oldPrice == null || oldPrice.compareTo(newPrice) != 0;
    }

    private void insertPriceLog(Long productId, BigDecimal oldPrice, BigDecimal newPrice) {
        PriceLog log = new PriceLog();
        log.setProductId(productId);
        log.setOldPrice(oldPrice);
        log.setNewPrice(newPrice);
        log.setChangeSource("手工");
        log.setEffectDate(LocalDate.now());
        priceLogMapper.insert(log);
    }
}
