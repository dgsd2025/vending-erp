package top.aole.vend.modules.bi.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.bi.dto.BiDtos;
import top.aole.vend.modules.bi.service.BiService;

/**
 * BI 经营分析接口(M4-1,§10.1):只读模块,零写接口。
 * 口径:毛利=§13(实收−移动加权成本);矩阵取不到的格子=null(前端显「—」),禁造假。
 */
@Api(tags = "BI · 经营分析(六维矩阵/四象限/连带/缺货损失/调价对比)")
@RestController
@RequestMapping("/v1/bi")
@RequiredArgsConstructor
public class BiController {

    private final BiService biService;

    @ApiOperation("维度×指标矩阵:dim=machine|category|product|slot|timeslot|supplier(§10.1 六维,取不到=null)")
    @GetMapping("/matrix")
    public R<BiDtos.MatrixResp> matrix(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "machine") String dim) {
        return R.ok(biService.matrix(month, dim));
    }

    @ApiOperation("单品四象限:销量×毛利率,阈值=中位数;明星/引流/利基/淘汰;无成本单品单列「成本待补」")
    @GetMapping("/quadrant")
    public R<BiDtos.QuadrantResp> quadrant(@RequestParam(required = false) String month) {
        return R.ok(biService.quadrant(month));
    }

    @ApiOperation("客单价与连带(同订单多件 TOP 组合)+ 支付方式占比")
    @GetMapping("/basket")
    public R<BiDtos.BasketResp> basket(@RequestParam(required = false) String month) {
        return R.ok(biService.basket(month));
    }

    @ApiOperation("缺货损失估算:缺货天数(锚点+增量逐日回放)×有货日日均毛利;无锚点不估")
    @GetMapping("/stockout-loss")
    public R<BiDtos.StockoutLossResp> stockoutLoss(@RequestParam(required = false) String month) {
        return R.ok(biService.stockoutLoss(month));
    }

    @ApiOperation("调价前后 14 天对比(price_log 有生效日的记录;后窗不足标 partial)")
    @GetMapping("/price-compare")
    public R<BiDtos.PriceCompareResp> priceCompare() {
        return R.ok(biService.priceCompare());
    }
}
