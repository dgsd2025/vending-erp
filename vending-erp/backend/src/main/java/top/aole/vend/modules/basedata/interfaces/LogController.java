package top.aole.vend.modules.basedata.interfaces;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.domain.entity.OpLog;
import top.aole.vend.modules.basedata.domain.entity.PriceLog;
import top.aole.vend.modules.basedata.infrastructure.mapper.OpLogMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.PriceLogMapper;

/**
 * 留痕查询接口(只读):操作日志 / 改价记录。日志永不删,没有写接口。
 */
@Api(tags = "基础档案 · 留痕查询")
@RestController
@RequestMapping("/v1/basedata")
@RequiredArgsConstructor
public class LogController {

    private final OpLogMapper opLogMapper;
    private final PriceLogMapper priceLogMapper;

    @ApiOperation("操作日志分页(可按对象类型/ID 过滤)")
    @GetMapping("/op-logs")
    public R<Page<OpLog>> opLogs(@RequestParam(defaultValue = "1") long current,
                                 @RequestParam(defaultValue = "20") long size,
                                 @RequestParam(required = false) String targetType,
                                 @RequestParam(required = false) Long targetId) {
        LambdaQueryWrapper<OpLog> qw = new LambdaQueryWrapper<>();
        qw.eq(StrUtil.isNotBlank(targetType), OpLog::getTargetType, targetType)
                .eq(targetId != null, OpLog::getTargetId, targetId)
                .orderByDesc(OpLog::getId);
        return R.ok(opLogMapper.selectPage(new Page<>(current, size), qw));
    }

    @ApiOperation("改价记录分页(可按商品过滤)")
    @GetMapping("/price-logs")
    public R<Page<PriceLog>> priceLogs(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) Long productId) {
        LambdaQueryWrapper<PriceLog> qw = new LambdaQueryWrapper<>();
        qw.eq(productId != null, PriceLog::getProductId, productId)
                .orderByDesc(PriceLog::getId);
        return R.ok(priceLogMapper.selectPage(new Page<>(current, size), qw));
    }
}
