package top.aole.vend.modules.settle.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.settle.domain.entity.Deduction;
import top.aole.vend.modules.settle.domain.entity.Payment;
import top.aole.vend.modules.settle.dto.SettleDtos;
import top.aole.vend.modules.settle.mapper.PayableQueryMapper;
import top.aole.vend.modules.settle.service.DeductionService;
import top.aole.vend.modules.settle.service.PayableService;
import top.aole.vend.modules.settle.service.PaymentService;
import top.aole.vend.modules.settle.service.SettleBillService;

import javax.validation.Valid;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 应付供应商全链接口(M3-2,业财一体样板流程 §9.2):
 * 供应商往来卡/对账单(p8)· 结算单(自动生成,老板复核确认)· 付款单(凭证硬门禁)· 抵扣确认单。
 *
 * 注意:**没有"新建结算单"接口**——结算单唯一生产者是采购入库确认事件(SettleBillGenerator);
 * 结算单复核限老板角色(X-User-Role 占位头,与解锁同一把尺子)。user_id 暂 0L 占位(SSO 后替换)。
 */
@Api(tags = "应付供应商全链 · 结算单/付款/抵扣/对账单")
@RestController
@RequestMapping("/v1/settle")
@RequiredArgsConstructor
public class SettleController {

    private static final Long UID = 0L;

    private final PayableService payableService;
    private final SettleBillService settleBillService;
    private final PaymentService paymentService;
    private final DeductionService deductionService;
    private final PayableQueryMapper payableQueryMapper;

    // ============================== 供应商往来(p8) ==============================

    @ApiOperation("供应商往来卡列表(应付余额实时算=期初+Σ采购−Σ退货−Σ抵扣−Σ付款;逾期黄灯/预付/待抵扣张数)")
    @GetMapping("/suppliers/overview")
    public R<List<SettleDtos.SupplierOverviewRow>> overview() {
        return R.ok(payableService.overview());
    }

    @ApiOperation("对账单(期初+明细+期末,发微信核对的数据源)")
    @GetMapping("/suppliers/{supplierId}/statement")
    public R<SettleDtos.StatementResp> statement(@PathVariable Long supplierId) {
        return R.ok(payableService.statement(supplierId));
    }

    @ApiOperation("对账单一键导出 xlsx")
    @GetMapping("/suppliers/{supplierId}/statement/export")
    public ResponseEntity<byte[]> statementExport(@PathVariable Long supplierId) throws UnsupportedEncodingException {
        byte[] bytes = payableService.statementXlsx(supplierId);
        String name = URLEncoder.encode("对账单-" + supplierId + "-" + LocalDate.now() + ".xlsx", "UTF-8")
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
                .body(bytes);
    }

    // ============================== 结算单(唯一生产者=采购入库确认事件) ==============================

    @ApiOperation("结算单列表(带来源单号;status/supplierId 可筛;红字单 direction=红字)")
    @GetMapping("/bills")
    public R<List<Map<String, Object>>> bills(@RequestParam(required = false) Long supplierId,
                                              @RequestParam(required = false) String status) {
        return R.ok(payableQueryMapper.billList(supplierId, status));
    }

    @ApiOperation("老板复核确认结算单(§9.2 确认点②,限老板角色头):勾选带入同供应商待抵扣+自动冲抵在挂红字 → 待付款")
    @PostMapping("/bills/{id}/confirm")
    public R<SettleBillService.ConfirmResult> confirmBill(@PathVariable Long id,
                                                          @RequestBody(required = false) SettleDtos.BillConfirmReq req,
                                                          @RequestHeader(value = Operators.HEADER, required = false) String op,
                                                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        List<Long> dedIds = req == null ? null : req.getDeductionIds();
        return R.ok(settleBillService.confirm(id, dedIds, UID, Operators.resolve(op), Operators.resolve(role)));
    }

    // ============================== 付款单(凭证硬门禁) ==============================

    @ApiOperation("付款单列表(带账户名/结算单号)")
    @GetMapping("/payments")
    public R<List<Map<String, Object>>> payments(@RequestParam(required = false) Long supplierId) {
        return R.ok(payableQueryMapper.paymentList(supplierId));
    }

    @ApiOperation("录入付款单(付给谁/从哪个账户/多少钱;可选核销结算单;确认前先传转账截图凭证)")
    @PostMapping("/payments")
    public R<Long> createPayment(@Valid @RequestBody SettleDtos.PaymentCreateReq req,
                                 @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(paymentService.create(req, UID, Operators.resolve(op)));
    }

    @ApiOperation("确认付款:凭证硬门禁(无转账截图拒绝)→ 落流水(供应商付款)→ 金额=实结自动核销全链闭环;≠实结差异挂起")
    @PostMapping("/payments/{id}/confirm")
    public R<Payment> confirmPayment(@PathVariable Long id,
                                     @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(paymentService.confirm(id, UID, Operators.resolve(op)));
    }

    @ApiOperation("差异挂起处理:补说明闭环(改单请走红冲/成本调整)")
    @PostMapping("/payments/{id}/resolve-diff")
    public R<Payment> resolveDiff(@PathVariable Long id,
                                  @Valid @RequestBody SettleDtos.DiffResolveReq req,
                                  @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(paymentService.resolveDiff(id, req.getNote(), UID, Operators.resolve(op)));
    }

    @ApiOperation("作废付款单(M3-9 七律修复:仅待付款——钱没动;备注强制留痕)")
    @PostMapping("/payments/{id}/void")
    public R<Void> voidPayment(@PathVariable Long id,
                               @Valid @RequestBody SettleDtos.DiffResolveReq req,
                               @RequestHeader(value = Operators.HEADER, required = false) String op) {
        paymentService.voidPayment(id, req.getNote(), UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("红冲付款单(钱已动的唯一逆向):负额红冲行+退款流水+结算单回待付款;备注强制留痕")
    @PostMapping("/payments/{id}/red-flush")
    public R<Long> redFlushPayment(@PathVariable Long id,
                                   @Valid @RequestBody SettleDtos.DiffResolveReq req,
                                   @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(paymentService.redFlush(id, req.getNote(), UID, Operators.resolve(op)));
    }

    @ApiOperation("应付逾期 aging(M3-9 七律修复:驾驶舱红灯只读数据口——逾期家数/最长天数/明细)")
    @GetMapping("/payable-aging")
    public R<SettleDtos.PayableAgingResp> payableAging() {
        return R.ok(payableService.payableAging());
    }

    // ============================== 抵扣确认单 ==============================

    @ApiOperation("抵扣确认单列表(status=待抵扣 即结算单确认弹窗的勾选源)")
    @GetMapping("/deductions")
    public R<List<Deduction>> deductions(@RequestParam(required = false) Long supplierId,
                                         @RequestParam(required = false) String status) {
        return R.ok(deductionService.list(supplierId, status));
    }

    @ApiOperation("录入抵扣确认单(供应商必填防串户 P2-11;厂家结账凭证走 /v1/money/attachments refType=deduction)")
    @PostMapping("/deductions")
    public R<Long> createDeduction(@Valid @RequestBody SettleDtos.DeductionCreateReq req,
                                   @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(deductionService.create(req, UID, Operators.resolve(op)));
    }

    @ApiOperation("作废抵扣确认单(仅待抵扣)")
    @PutMapping("/deductions/{id}/void")
    public R<Void> voidDeduction(@PathVariable Long id,
                                 @RequestHeader(value = Operators.HEADER, required = false) String op) {
        deductionService.voidDeduction(id, UID, Operators.resolve(op));
        return R.ok(null);
    }
}
