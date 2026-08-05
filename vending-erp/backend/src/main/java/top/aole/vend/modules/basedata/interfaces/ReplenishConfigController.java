package top.aole.vend.modules.basedata.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.ReplenishConfigService;
import top.aole.vend.modules.basedata.domain.entity.ReplenishConfig;

import java.util.List;

/**
 * 补货参数接口:全局 + 按 SKU/机器覆盖的读写。每次改动写 op_log(旧值→新值→改动人)。
 */
@Api(tags = "基础档案 · 参数与阈值")
@RestController
@RequestMapping("/v1/basedata/replenish-configs")
@RequiredArgsConstructor
public class ReplenishConfigController {

    private final ReplenishConfigService configService;

    @ApiOperation("全部参数(全局+覆盖行)")
    @GetMapping
    public R<List<ReplenishConfig>> list() {
        return R.ok(configService.list());
    }

    @ApiOperation("全局参数(未设置时返回内置默认值)")
    @GetMapping("/global")
    public R<ReplenishConfig> getGlobal() {
        return R.ok(configService.getGlobal());
    }

    @ApiOperation("保存(upsert):machineId/productId 为 0 或不传=全局")
    @PutMapping
    public R<ReplenishConfig> save(@RequestBody ReplenishConfig config,
                                   @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(configService.save(config, Operators.resolve(userName)));
    }

    @ApiOperation("删除覆盖行(回落全局;全局行不可删)")
    @DeleteMapping("/{id}")
    public R<Void> deleteOverride(@PathVariable Long id,
                                  @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        configService.deleteOverride(id, Operators.resolve(userName));
        return R.ok();
    }
}
