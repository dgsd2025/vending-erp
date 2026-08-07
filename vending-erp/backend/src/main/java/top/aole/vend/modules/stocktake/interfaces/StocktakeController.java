package top.aole.vend.modules.stocktake.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos;
import top.aole.vend.modules.stocktake.service.StocktakeService;

import javax.validation.Valid;
import java.util.List;

/**
 * 盘点接口(M2-4):创建(快照账面)→ 录实盘(只录差异)→ 提交 → 五步向导(查账/确认过账+锚点)。
 * user_id 暂 0L 占位(与 doc/purchase 一致,SSO 后替换);老板确认走 X-User-Role 占位头(同红冲)。
 */
@Api(tags = "盘点 · 五步向导/损耗统计")
@RestController
@RequestMapping("/v1/stocktake")
@RequiredArgsConstructor
public class StocktakeController {

    private static final Long UID = 0L;

    private final StocktakeService stocktakeService;

    @ApiOperation("新建盘点单:选范围(仓库/机器)→ 系统快照账面数存 book_qty")
    @PostMapping
    public R<Long> create(@Valid @RequestBody StocktakeDtos.CreateReq req) {
        return R.ok(stocktakeService.create(req, UID));
    }

    @ApiOperation("盘点单列表(带机器名+差异汇总)")
    @GetMapping
    public R<List<StocktakeDtos.ListRow>> list(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(stocktakeService.list(limit));
    }

    @ApiOperation("盘点单详情(头+明细,差异行在前)")
    @GetMapping("/{id}")
    public R<StocktakeDtos.DetailResp> detail(@PathVariable Long id) {
        return R.ok(stocktakeService.detail(id));
    }

    @ApiOperation("录入实盘:只提交差异行(整包替换,未提交=视同相符);差异行必选原因")
    @PutMapping("/{id}/items")
    public R<Void> saveItems(@PathVariable Long id, @Valid @RequestBody StocktakeDtos.SaveItemsReq req) {
        stocktakeService.saveItems(id, req, UID);
        return R.ok(null);
    }

    @ApiOperation("提交盘点:进行中→待确认(差异行原因完整性校验)")
    @PostMapping("/{id}/submit")
    public R<Void> submit(@PathVariable Long id) {
        stocktakeService.submit(id, UID);
        return R.ok(null);
    }

    @ApiOperation("五步向导第1步·系统自动查账:近7天导入/单据变更/线下补录 + 可能漏导清单")
    @GetMapping("/{id}/precheck")
    public R<StocktakeDtos.PrecheckResp> precheck(@PathVariable Long id) {
        return R.ok(stocktakeService.precheck(id));
    }

    @ApiOperation("确认盘点(第3步):仓库→盘盈/盘亏单过账;机器→退库+盘亏配对+快照锚点;" +
            ">¥50 需 X-User-Role=老板;锁账期需 bossOverride+备注")
    @PostMapping("/{id}/confirm")
    public R<StocktakeDtos.ConfirmResp> confirm(@PathVariable Long id,
                                                @RequestBody(required = false) StocktakeDtos.ConfirmReq req,
                                                @RequestHeader(value = "X-User-Role", required = false) String role) {
        StocktakeDtos.ConfirmReq body = req == null ? new StocktakeDtos.ConfirmReq() : req;
        return R.ok(stocktakeService.confirm(id, UID, Operators.resolve(role),
                body.isBossOverride(), body.getNote()));
    }

    @ApiOperation("作废盘点:仅进行中/待确认(未过账)")
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        stocktakeService.cancel(id, UID);
        return R.ok(null);
    }

    @ApiOperation("损耗统计:按原因×月份汇总件数/成本额(豁免行不算损耗),供报表/驾驶舱")
    @GetMapping("/loss-stats")
    public R<List<StocktakeDtos.LossStatRow>> lossStats(@RequestParam(defaultValue = "6") int months) {
        return R.ok(stocktakeService.lossStats(months));
    }
}
