package top.aole.vend.modules.basedata.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.ProductService;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;

import javax.validation.Valid;

/**
 * 商品档案接口。完整路径 /api/v1/basedata/products。
 * 铁律:停售≠删除,有流水的永不删——因此没有 DELETE 接口,只有状态流转。
 */
@Api(tags = "基础档案 · 商品")
@RestController
@RequestMapping("/v1/basedata/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @ApiOperation("分页列表(名称/编码/条码关键字 + 分类 + 状态)")
    @GetMapping
    public R<Page<Product>> page(@RequestParam(defaultValue = "1") long current,
                                 @RequestParam(defaultValue = "20") long size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(required = false) String productStatus) {
        return R.ok(productService.page(current, size, keyword, category, productStatus));
    }

    @ApiOperation("详情")
    @GetMapping("/{id}")
    public R<Product> detail(@PathVariable Long id) {
        return R.ok(productService.getById(id));
    }

    @ApiOperation("新建")
    @PostMapping
    public R<Product> create(@RequestBody Product product,
                             @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(productService.create(product, Operators.resolve(userName)));
    }

    @ApiOperation("编辑(改售价会自动写 price_log)")
    @PutMapping("/{id}")
    public R<Product> update(@PathVariable Long id, @RequestBody Product product,
                             @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(productService.update(id, product, Operators.resolve(userName)));
    }

    @ApiOperation("状态流转:在售/清仓中/停售")
    @PutMapping("/{id}/status")
    public R<Product> changeStatus(@PathVariable Long id, @Valid @RequestBody Dtos.StatusReq req,
                                   @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(productService.changeStatus(id, req.getTargetStatus(), Operators.resolve(userName)));
    }
}
