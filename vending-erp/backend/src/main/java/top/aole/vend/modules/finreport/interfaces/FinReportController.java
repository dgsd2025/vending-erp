package top.aole.vend.modules.finreport.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.finreport.dto.FinReportDtos;
import top.aole.vend.modules.finreport.service.AssetSnapshotService;
import top.aole.vend.modules.finreport.service.ProfitReportService;

import java.util.List;

/**
 * 财务报表接口(M3-6):资产快照(即时/归档/趋势,p10 资产家底页)+ 简版利润表(报表页 Tab)。
 * user_id 暂 0L 占位(同 doc/money,SSO 后替换);经手人走 X-User-Name 头。
 */
@Api(tags = "财务报表 · 资产快照/简版利润表")
@RestController
@RequestMapping("/v1/finreport")
@RequiredArgsConstructor
public class FinReportController {

    private static final Long UID = 0L;

    private final AssetSnapshotService assetSnapshotService;
    private final ProfitReportService profitReportService;

    @ApiOperation("即时资产快照(§13.1:库存+待结算+现金+索赔应收−应付=净家底;五分项各带下钻来源列表)")
    @GetMapping("/assets")
    public R<FinReportDtos.AssetSnapshotResp> assets() {
        return R.ok(assetSnapshotService.current());
    }

    @ApiOperation("月度归档(幂等重算当月覆盖;已锁账月永不重算)")
    @PostMapping("/assets/archive")
    public R<FinReportDtos.SnapshotRow> archive(@RequestParam String period,
                                                @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(assetSnapshotService.archive(period, UID, Operators.resolve(op)));
    }

    @ApiOperation("月度归档列表(新→旧)")
    @GetMapping("/assets/archives")
    public R<List<FinReportDtos.SnapshotRow>> archives() {
        return R.ok(assetSnapshotService.archives());
    }

    @ApiOperation("净资产趋势(旧→新,近 N 月折线数据)")
    @GetMapping("/assets/trend")
    public R<List<FinReportDtos.SnapshotRow>> trend(@RequestParam(defaultValue = "12") int months) {
        return R.ok(assetSnapshotService.trend(months));
    }

    @ApiOperation("简版利润表(按入账月;§13.1 行结构;锁账月标已归档不重算;含 lock_diff_note 提示)")
    @GetMapping("/profit")
    public R<FinReportDtos.ProfitResp> profit(@RequestParam(required = false) String period) {
        return R.ok(profitReportService.monthly(period));
    }
}
