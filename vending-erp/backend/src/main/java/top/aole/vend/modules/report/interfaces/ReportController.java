package top.aole.vend.modules.report.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.report.dto.ReportDtos;
import top.aole.vend.modules.report.service.ReportService;

import java.util.List;

/**
 * 报表接口(M1-6):毛利报表 / 进销存汇总 / 库存查询 / 成本重算。
 */
@Api(tags = "报表 · 毛利/进销存/库存")
@RestController
@RequestMapping("/v1/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @ApiOperation("毛利报表:按月 × SKU/机器(dim=sku|machine);无成本 SKU 毛利显「—(成本待补)」")
    @GetMapping("/gross-margin")
    public R<ReportDtos.GrossMarginResp> grossMargin(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "sku") String dim) {
        return R.ok(reportService.grossMargin(month, dim));
    }

    @ApiOperation("月度进销存汇总:期初/入库/出库/期末(数量+金额,全局口径=仓库+机器合计)")
    @GetMapping("/inventory-summary")
    public R<ReportDtos.InventorySummaryResp> inventorySummary(
            @RequestParam(required = false) String month) {
        return R.ok(reportService.inventorySummary(month));
    }

    @ApiOperation("库存查询:仓库现存(Σ流水)+ 各机器现存(锚点+增量推算)+ 合计,负库存红灯行")
    @GetMapping("/stock")
    public R<ReportDtos.StockResp> stock() {
        return R.ok(reportService.stock());
    }

    @ApiOperation("单品库存流水(点开看流水,每一笔都有单据)")
    @GetMapping("/stock/{productId}/ledger")
    public R<List<ReportDtos.StockLedgerRow>> productLedger(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "100") int limit) {
        return R.ok(reportService.productLedger(productId, limit));
    }

    @ApiOperation("单品详情聚合(M1-9,p14 体检报告):档案+两级库存+30天销量毛利+周走势+机器分布+采购史(只读)")
    @GetMapping("/product/{productId}/overview")
    public R<ReportDtos.ProductOverviewResp> productOverview(@PathVariable Long productId) {
        return R.ok(reportService.productOverview(productId));
    }

    @ApiOperation("机器详情聚合(M1-9,p15):档案+当月销售毛利+14天走势+TOP SKU+货道 planogram+补货史(只读)")
    @GetMapping("/machine/{machineId}/overview")
    public R<ReportDtos.MachineOverviewResp> machineOverview(@PathVariable Long machineId) {
        return R.ok(reportService.machineOverview(machineId));
    }

    @ApiOperation("成本重算回写:sale_record.cost_amount + ledger 出库/转移行成本快照(附录C)")
    @PostMapping("/cost/recalc")
    public R<ReportDtos.RecalcResp> recalc(
            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(reportService.recalc(Operators.resolve(userName)));
    }
}
