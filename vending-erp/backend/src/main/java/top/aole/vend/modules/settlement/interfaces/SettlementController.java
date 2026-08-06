package top.aole.vend.modules.settlement.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.settlement.dto.SettlementDtos;
import top.aole.vend.modules.settlement.service.SettlementService;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/**
 * 平台结算双模式接口(M3-3,附录D):
 * PLATFORM=平台结算单(核销+两差对账)/ DIRECT=商户账单核对单(只对差)/ UNSET=横幅+假设对比预览。
 * 凭证走通用件 POST /v1/money/attachments(refType=settlement);user_id 暂 0L 占位(SSO 后替换)。
 */
@Api(tags = "平台结算 · 双模式(结算单/核对单/兑换ROI)")
@RestController
@RequestMapping("/v1/settlement")
@RequiredArgsConstructor
public class SettlementController {

    private static final Long UID = 0L;

    private final SettlementService settlementService;

    @ApiOperation("总览(模式感知):PLATFORM=待结算余额;DIRECT=直连说明;UNSET=横幅+两模式假设对比(非正式)")
    @GetMapping("/overview")
    public R<SettlementDtos.OverviewResp> overview() {
        return R.ok(settlementService.overview());
    }

    @ApiOperation("结算单/核对单列表(带两差红绿灯)")
    @GetMapping("/bills")
    public R<List<SettlementDtos.BillRow>> listBills(@RequestParam(required = false) String status) {
        return R.ok(settlementService.list(status));
    }

    @ApiOperation("录入(模式感知):PLATFORM=区间/账单额/手续费/到账/账户;DIRECT=区间/账单额;UNSET 拦")
    @PostMapping("/bills")
    public R<Long> createBill(@Valid @RequestBody SettlementDtos.BillCreateReq req,
                              @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(settlementService.create(req, UID, Operators.resolve(op)));
    }

    @ApiOperation("确认:PLATFORM=快照+两差+回填+两笔流水(平台账单凭证必传);DIRECT=只对差不动钱")
    @PostMapping("/bills/{id}/confirm")
    public R<SettlementDtos.ConfirmResult> confirmBill(@PathVariable Long id,
                                                       @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(settlementService.confirm(id, UID, Operators.resolve(op)));
    }

    @ApiOperation("差异挂起复核收口(说明必填留痕)")
    @PostMapping("/bills/{id}/resolve-diff")
    public R<Void> resolveDiff(@PathVariable Long id, @RequestBody SettlementDtos.ResolveDiffReq req,
                               @RequestHeader(value = Operators.HEADER, required = false) String op) {
        settlementService.resolveDiff(id, req == null ? null : req.getNote(), UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("作废(仅待核对可作废;改模式后的旧单走这里重录)")
    @PostMapping("/bills/{id}/void")
    public R<Void> voidBill(@PathVariable Long id,
                            @RequestHeader(value = Operators.HEADER, required = false) String op) {
        settlementService.voidBill(id, UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("兑换活动 ROI:Σ兑换出货成本 vs Σ厂家补贴确认额(deduction 非作废;补贴对冲兑换成本)")
    @GetMapping("/exchange-roi")
    public R<SettlementDtos.ExchangeRoiResp> exchangeRoi(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(settlementService.exchangeRoi(from, to));
    }
}
