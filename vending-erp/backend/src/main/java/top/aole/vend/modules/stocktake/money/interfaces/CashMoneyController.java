package top.aole.vend.modules.stocktake.money.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos;
import top.aole.vend.modules.stocktake.money.service.CashAdjustService;
import top.aole.vend.modules.stocktake.money.service.CashCheckService;

import javax.validation.Valid;
import java.util.List;

/**
 * M3-5 接口:资金调整单(P1-7 钱盘差异唯一出口)+ 月度钱盘三核对(§8.2 D2)。
 *
 * user_id 暂 0L 占位(与 doc/stocktake 一致,SSO 后替换);经手人 X-User-Name 头;
 * 老板确认走 X-User-Role 占位头(同红冲/盘点>¥50 一把尺子)。
 */
@Api(tags = "钱盘 · 资金调整单/月度三核对")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class CashMoneyController {

    private static final Long UID = 0L;

    private final CashAdjustService cashAdjustService;
    private final CashCheckService cashCheckService;

    // ============================== 资金调整单 ==============================

    @ApiOperation("新建资金调整单并提交(账户/带符号金额±/原因枚举,其他必备注;虚拟账户建单即拦)")
    @PostMapping("/cash-adjust")
    public R<Long> createAdjust(@Valid @RequestBody CashMoneyDtos.AdjustCreateReq req,
                                @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(cashAdjustService.create(req, UID, Operators.resolve(op)));
    }

    @ApiOperation("老板确认资金调整单(X-User-Role=老板)→ DocStatusGuard 防双击 → 落 cash_flow → 单据完成")
    @PostMapping("/cash-adjust/{docId}/confirm")
    public R<Void> confirmAdjust(@PathVariable Long docId,
                                 @RequestHeader(value = "X-User-Role", required = false) String role,
                                 @RequestHeader(value = Operators.HEADER, required = false) String op) {
        cashAdjustService.confirm(docId, UID, Operators.resolve(role), Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("资金调整单列表(带账户名/方向/原因/单据状态)")
    @GetMapping("/cash-adjust")
    public R<List<CashMoneyDtos.AdjustRow>> listAdjusts(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(cashAdjustService.list(limit));
    }

    // ============================== 钱盘三核对 ==============================

    @ApiOperation("开始本月钱盘:快照三类系统数(账户余额/平台待结算/供应商应付);同时只许一张进行中")
    @PostMapping("/cash-check")
    public R<Long> startCheck(@RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(cashCheckService.start(UID, Operators.resolve(op)));
    }

    @ApiOperation("核对记录列表(历史归档)")
    @GetMapping("/cash-check")
    public R<List<CashMoneyDtos.CheckListRow>> listChecks(@RequestParam(defaultValue = "12") int limit) {
        return R.ok(cashCheckService.list(limit));
    }

    @ApiOperation("当前进行中的核对(续盘入口;没有返回 null)")
    @GetMapping("/cash-check/current")
    public R<CashMoneyDtos.CheckDetailResp> currentCheck() {
        return R.ok(cashCheckService.current());
    }

    @ApiOperation("核对记录详情(三分区:账户/平台/应付)")
    @GetMapping("/cash-check/{id}")
    public R<CashMoneyDtos.CheckDetailResp> checkDetail(@PathVariable Long id) {
        return R.ok(cashCheckService.detail(id));
    }

    @ApiOperation("录实际数(手填;差异=实际−系统 落库)")
    @PutMapping("/cash-check/{id}/items")
    public R<Void> saveActuals(@PathVariable Long id,
                               @Valid @RequestBody CashMoneyDtos.SaveActualsReq req,
                               @RequestHeader(value = Operators.HEADER, required = false) String op) {
        cashCheckService.saveActuals(id, req, UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("账户差异行 → 一键生成资金调整单(唯一出口;防重复生成)")
    @PostMapping("/cash-check/{id}/items/{itemId}/adjust")
    public R<Long> genAdjust(@PathVariable Long id, @PathVariable Long itemId,
                             @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(cashCheckService.genAdjust(id, itemId, UID, Operators.resolve(op)));
    }

    @ApiOperation("应付不符出口两按钮:补录(返跳转路由)/红冲(返来源单据id)——只跳转不重造")
    @PostMapping("/cash-check/{id}/items/{itemId}/exit")
    public R<CashMoneyDtos.PayableExitResp> payableExit(@PathVariable Long id, @PathVariable Long itemId,
                                                        @Valid @RequestBody CashMoneyDtos.PayableExitReq req,
                                                        @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(cashCheckService.markPayableExit(id, itemId, req.getAction(), UID, Operators.resolve(op)));
    }

    @ApiOperation("完成钱盘(守卫:实际数全填 + 差异行走了出口或留说明;条件更新防双击)")
    @PostMapping("/cash-check/{id}/finish")
    public R<Void> finishCheck(@PathVariable Long id,
                               @RequestHeader(value = Operators.HEADER, required = false) String op) {
        cashCheckService.finish(id, UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("作废进行中的核对(记录不删,状态置已作废)")
    @PostMapping("/cash-check/{id}/cancel")
    public R<Void> cancelCheck(@PathVariable Long id,
                               @RequestHeader(value = Operators.HEADER, required = false) String op) {
        cashCheckService.cancel(id, UID, Operators.resolve(op));
        return R.ok(null);
    }
}
