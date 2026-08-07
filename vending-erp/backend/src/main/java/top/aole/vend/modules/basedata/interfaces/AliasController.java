package top.aole.vend.modules.basedata.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.AliasService;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;

import javax.validation.Valid;

/**
 * SKU 别名接口(含 alias_pending 待绑定队列,供 M1-3 导入用)。
 * 绑定键 = 后台商品编号 + 条码,不绑名称(冲刺 0 拍板)。
 */
@Api(tags = "基础档案 · SKU 别名")
@RestController
@RequestMapping("/v1/basedata")
@RequiredArgsConstructor
public class AliasController {

    private final AliasService aliasService;

    @ApiOperation("别名分页列表")
    @GetMapping("/aliases")
    public R<Page<SkuAlias>> page(@RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "20") long size,
                                  @RequestParam(required = false) Long productId,
                                  @RequestParam(required = false) String keyword) {
        return R.ok(aliasService.page(current, size, productId, keyword));
    }

    @ApiOperation("绑定:后台商品编号+条码 → SKU")
    @PostMapping("/aliases/bind")
    public R<SkuAlias> bind(@Valid @RequestBody Dtos.BindAliasReq req,
                            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(aliasService.bind(req, Operators.resolve(userName)));
    }

    @ApiOperation("解绑(op_log 留痕)")
    @DeleteMapping("/aliases/{id}")
    public R<Void> unbind(@PathVariable Long id,
                          @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        aliasService.unbind(id, Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("待绑定队列分页(默认全部,可按状态筛)")
    @GetMapping("/alias-pending")
    public R<Page<AliasPending>> pendingPage(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String pendingStatus) {
        return R.ok(aliasService.pendingPage(current, size, pendingStatus));
    }

    @ApiOperation("确认绑定:待绑定项 → 写入 sku_alias")
    @PostMapping("/alias-pending/{id}/confirm")
    public R<SkuAlias> confirmPending(@PathVariable Long id, @Valid @RequestBody Dtos.ConfirmPendingReq req,
                                      @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(aliasService.confirmPending(id, req.getProductId(), Operators.resolve(userName)));
    }

    @ApiOperation("忽略待绑定项")
    @PostMapping("/alias-pending/{id}/ignore")
    public R<Void> ignorePending(@PathVariable Long id,
                                 @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        aliasService.ignorePending(id, Operators.resolve(userName));
        return R.ok();
    }
}
