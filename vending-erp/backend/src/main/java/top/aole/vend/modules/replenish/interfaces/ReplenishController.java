package top.aole.vend.modules.replenish.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.replenish.service.ReplenishEngine;

import java.time.LocalDate;
import java.util.Map;

/**
 * AI 补货接口(M2-1/M2-2):规则引擎出数字,LLM 只出解释。
 * 参数读写复用 M1-2:/v1/basedata/replenish-configs(全局+SKU/机器覆盖,改动写 op_log)。
 */
@Api(tags = "补货 · (R,S) 引擎/建议/🔬过程")
@RestController
@RequestMapping("/v1/replenish")
@RequiredArgsConstructor
public class ReplenishController {

    private final ReplenishEngine engine;

    @ApiOperation("机器侧建议列表(par level 补满货道;默认最近一次生成日)")
    @GetMapping("/machine-suggestions")
    public R<Map<String, Object>> machineSuggestions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        return R.ok(engine.suggestions(ReplenishEngine.TYPE_MACHINE, planDate));
    }

    @ApiOperation("仓库侧采购建议列表((R,S) 公式/慢销 min-max;默认最近一次生成日)")
    @GetMapping("/purchase-suggestions")
    public R<Map<String, Object>> purchaseSuggestions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        return R.ok(engine.suggestions(ReplenishEngine.TYPE_PURCHASE, planDate));
    }

    @ApiOperation("重新计算(幂等:同日重算覆盖「建议」行,已忽略/已生成配货单保留)")
    @PostMapping("/recalc")
    public R<Map<String, Object>> recalc(
            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(engine.recalc(Operators.resolve(userName)));
    }

    @ApiOperation("忽略一条建议")
    @PostMapping("/plans/{id}/ignore")
    public R<Void> ignore(@PathVariable Long id,
                          @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        engine.setIgnored(id, true, Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("采纳一条建议(转订货单后回写)")
    @PostMapping("/plans/{id}/adopt")
    public R<Void> adopt(@PathVariable Long id,
                         @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        engine.adopt(id, Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("恢复一条已忽略的建议")
    @PostMapping("/plans/{id}/restore")
    public R<Void> restore(@PathVariable Long id,
                           @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        engine.setIgnored(id, false, Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("🔬 过程详情:公式与输入值快照 + AI 透明四件套(推理/输出/置信/原始数据)")
    @GetMapping("/plans/{id}")
    public R<Map<String, Object>> planDetail(@PathVariable Long id) {
        return R.ok(engine.planDetail(id));
    }
}
