package top.aole.vend.modules.expense.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.expense.dto.ExpenseDtos;
import top.aole.vend.modules.expense.service.ExpenseService;
import top.aole.vend.modules.expense.service.OfflineSaleService;

import javax.validation.Valid;
import java.util.List;

/**
 * 支出单(杂费/设备)+ 设备台账 + 线下收入复合单接口(M3-4,§9.3 场景7/8 · P2-13)。
 * 凭证上传复用钱账基座 /v1/money/attachments(refType=expense)。
 */
@Api(tags = "支出单/设备台账/线下收入复合单")
@RestController
@RequiredArgsConstructor
public class ExpenseController {

    private static final Long UID = 0L;

    private final ExpenseService expenseService;
    private final OfflineSaleService offlineSaleService;

    // ============================== 支出单 ==============================

    @ApiOperation("支出单列表(可按状态筛:待确认/已完成)")
    @GetMapping("/v1/expenses")
    public R<List<ExpenseDtos.ExpenseRow>> list(@RequestParam(required = false) String status) {
        return R.ok(expenseService.list(status));
    }

    @ApiOperation("录入支出单(电费/维修/杂支/设备购置;设备购置必填设备名)→ 待确认")
    @PostMapping("/v1/expenses")
    public R<Long> create(@Valid @RequestBody ExpenseDtos.ExpenseCreateReq req,
                          @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(expenseService.create(req, UID, Operators.resolve(op)));
    }

    @ApiOperation("确认支出单(凭证必传→落流水杂费行;设备购置同步建台账行)")
    @PostMapping("/v1/expenses/{id}/confirm")
    public R<ExpenseDtos.ExpenseRow> confirm(@PathVariable Long id,
                                             @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(expenseService.confirm(id, UID, Operators.resolve(op)));
    }

    @ApiOperation("作废支出单(M3-9 七律修复:仅待确认——钱没动;备注强制留痕)")
    @PostMapping("/v1/expenses/{id}/void")
    public R<Void> voidExpense(@PathVariable Long id,
                               @RequestBody ExpenseDtos.NoteReq req,
                               @RequestHeader(value = Operators.HEADER, required = false) String op) {
        expenseService.voidExpense(id, req == null ? null : req.getNote(), UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("红冲支出单(已确认的唯一逆向):负额红冲行+反向流水+设备台账行标退回;备注强制留痕")
    @PostMapping("/v1/expenses/{id}/red-flush")
    public R<Long> redFlushExpense(@PathVariable Long id,
                                   @RequestBody ExpenseDtos.NoteReq req,
                                   @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(expenseService.redFlush(id, req == null ? null : req.getNote(), UID, Operators.resolve(op)));
    }

    // ============================== 设备台账 ==============================

    @ApiOperation("设备台账列表(回本进度展示,不进流水)")
    @GetMapping("/v1/equipment")
    public R<List<ExpenseDtos.EquipmentRow>> listEquipment() {
        return R.ok(expenseService.listEquipment());
    }

    @ApiOperation("编辑设备台账(名称/关联机器/折余价值/状态 在用/报废/出售)")
    @PutMapping("/v1/equipment/{id}")
    public R<Void> updateEquipment(@PathVariable Long id,
                                   @Valid @RequestBody ExpenseDtos.EquipmentUpdateReq req,
                                   @RequestHeader(value = Operators.HEADER, required = false) String op) {
        expenseService.updateEquipment(id, req, UID, Operators.resolve(op));
        return R.ok(null);
    }

    // ============================== 线下收入复合单(P2-13) ==============================

    @ApiOperation("线下收入复合单:一次录入同事务生成 sale_record(线下补录,不入待结算)+ cash_flow(其他收入-平台外)+ 豁免标记")
    @PostMapping("/v1/offline-sales")
    public R<ExpenseDtos.OfflineSaleResp> createOfflineSale(
            @Valid @RequestBody ExpenseDtos.OfflineSaleReq req,
            @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(offlineSaleService.create(req, UID, Operators.resolve(op)));
    }

    @ApiOperation("近期线下补录列表")
    @GetMapping("/v1/offline-sales")
    public R<List<ExpenseDtos.OfflineSaleRow>> listOfflineSales(
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(offlineSaleService.listRecent(limit));
    }

    @ApiOperation("一键冲销线下复合单(M3-9 七律修复):三件套整体反向(销售红冲行+反向流水);老板守卫+备注强制")
    @PostMapping("/v1/offline-sales/{id}/reverse")
    public R<ExpenseDtos.OfflineSaleResp> reverseOfflineSale(
            @PathVariable Long id,
            @RequestBody ExpenseDtos.NoteReq req,
            @RequestHeader(value = Operators.HEADER, required = false) String op,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return R.ok(offlineSaleService.reverse(id, req == null ? null : req.getNote(),
                Operators.resolve(role), UID, Operators.resolve(op)));
    }
}
