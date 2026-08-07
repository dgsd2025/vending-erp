package top.aole.vend.modules.basedata.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.SupplierService;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;

import javax.validation.Valid;

/**
 * 供应商接口。停用保留历史往来,无 DELETE。
 */
@Api(tags = "基础档案 · 供应商")
@RestController
@RequestMapping("/v1/basedata/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @ApiOperation("分页列表")
    @GetMapping
    public R<Page<Supplier>> page(@RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "20") long size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String coopStatus) {
        return R.ok(supplierService.page(current, size, keyword, coopStatus));
    }

    @ApiOperation("详情")
    @GetMapping("/{id}")
    public R<Supplier> detail(@PathVariable Long id) {
        return R.ok(supplierService.getById(id));
    }

    @ApiOperation("新建(含结算方式/账期/期初应付)")
    @PostMapping
    public R<Supplier> create(@RequestBody Supplier supplier,
                              @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(supplierService.create(supplier, Operators.resolve(userName)));
    }

    @ApiOperation("编辑")
    @PutMapping("/{id}")
    public R<Supplier> update(@PathVariable Long id, @RequestBody Supplier supplier,
                              @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(supplierService.update(id, supplier, Operators.resolve(userName)));
    }

    @ApiOperation("合作状态流转:合作中/停用")
    @PutMapping("/{id}/status")
    public R<Supplier> changeStatus(@PathVariable Long id, @Valid @RequestBody Dtos.StatusReq req,
                                    @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(supplierService.changeStatus(id, req.getTargetStatus(), Operators.resolve(userName)));
    }
}
