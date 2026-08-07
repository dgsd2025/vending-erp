package top.aole.vend.common.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.vend.common.result.R;

/**
 * 健康检查。context-path=/api,完整路径 GET /api/v1/health。
 */
@Api(tags = "系统")
@RestController
@RequestMapping("/v1")
public class HealthController {

    @ApiOperation("健康检查")
    @GetMapping("/health")
    public R<String> health() {
        return R.ok("vending-erp backend alive");
    }
}
