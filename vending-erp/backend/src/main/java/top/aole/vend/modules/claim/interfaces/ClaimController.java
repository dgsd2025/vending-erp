package top.aole.vend.modules.claim.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.service.ClaimService;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.util.List;

/**
 * 索赔单接口(M3-4,§9.3 场景4):申请中→已到账(凭证门禁+落流水)/放弃(备注必填)。
 * 凭证上传复用钱账基座 /v1/money/attachments(refType=claim)。
 * user_id 暂 0L 占位(与 money 一致,SSO 后替换);经手人走 X-User-Name 头。
 */
@Api(tags = "索赔单 · 生命周期/索赔应收/净损耗")
@RestController
@RequestMapping("/v1/claims")
@RequiredArgsConstructor
public class ClaimController {

    private static final Long UID = 0L;

    private final ClaimService claimService;

    @ApiOperation("索赔单列表(可按状态筛:申请中/已到账/放弃)")
    @GetMapping
    public R<List<ClaimDtos.ClaimRow>> list(@RequestParam(required = false) String status) {
        return R.ok(claimService.list(status));
    }

    @ApiOperation("索赔单详情")
    @GetMapping("/{id}")
    public R<ClaimDtos.ClaimRow> detail(@PathVariable Long id) {
        return R.ok(claimService.detail(id));
    }

    @ApiOperation("发起索赔申请(盘亏归因=吞货/被盗行;金额默认=盘亏成本额,前端预填)")
    @PostMapping
    public R<Long> create(@Valid @RequestBody ClaimDtos.CreateReq req,
                          @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(claimService.create(req, UID, Operators.resolve(op)));
    }

    @ApiOperation("到账登记(赔付凭证必传→写流水 其他收入-赔付+回填 cash_flow_id)")
    @PostMapping("/{id}/receive")
    public R<ClaimDtos.ClaimRow> receive(@PathVariable Long id,
                                         @Valid @RequestBody ClaimDtos.ReceiveReq req,
                                         @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(claimService.receive(id, req, UID, Operators.resolve(op)));
    }

    @ApiOperation("放弃索赔(备注必填,退出索赔应收)")
    @PostMapping("/{id}/abandon")
    public R<Void> abandon(@PathVariable Long id,
                           @Valid @RequestBody ClaimDtos.AbandonReq req,
                           @RequestHeader(value = Operators.HEADER, required = false) String op) {
        claimService.abandon(id, req, UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("索赔应收 = Σ申请中金额(资产快照净流动资产第4项,M3-6 取数口)")
    @GetMapping("/receivable")
    public R<BigDecimal> receivable() {
        return R.ok(claimService.receivable());
    }

    @ApiOperation("净损耗 = 损耗(已确认盘亏/报损成本额)− 已获赔(损耗报表口,区间可空=全期)")
    @GetMapping("/net-shrinkage")
    public R<ClaimDtos.NetShrinkageResp> netShrinkage(@RequestParam(required = false) String fromPeriod,
                                                      @RequestParam(required = false) String toPeriod) {
        return R.ok(claimService.netShrinkage(fromPeriod, toPeriod));
    }
}
