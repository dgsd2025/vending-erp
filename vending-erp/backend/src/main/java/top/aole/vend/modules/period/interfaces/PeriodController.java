package top.aole.vend.modules.period.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.period.domain.entity.PeriodLock;
import top.aole.vend.modules.period.service.PeriodLockService;

import javax.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 期间管理接口(M1-7,P0-2 锁账×补导)。
 * user_id 暂 0L 占位(与 doc/purchase 一致);老板角色经 X-User-Role 头占位传入,SSO 后替换。
 */
@Api(tags = "期间 · 锁账/解锁/上期调整")
@RestController
@RequestMapping("/v1/period")
@RequiredArgsConstructor
public class PeriodController {

    private static final Long UID = 0L;

    private final PeriodLockService periodLockService;

    @ApiOperation("锁账状态:当前锁账线 + 锁账记录列表")
    @GetMapping("/locks")
    public R<Map<String, Object>> locks() {
        Map<String, Object> result = new HashMap<>();
        result.put("lockLine", periodLockService.lockLine());
        result.put("locks", periodLockService.listLocks());
        return R.ok(result);
    }

    @ApiOperation("锁账:锁定某 YYYY-MM 及之前所有月份")
    @PostMapping("/locks")
    public R<Long> lock(@RequestBody LockReq req) {
        return R.ok(periodLockService.lock(req.getPeriod(), UID, req.getNote()));
    }

    @ApiOperation("解锁:限老板角色(X-User-Role 占位,中文经 percent-encode)+强制备注,只能解当前锁账线")
    @PostMapping("/unlock")
    public R<Void> unlock(@RequestBody LockReq req,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        // 中文角色名走 HTTP 头会被 percent-encode,复用 Operators 解码占位方案
        periodLockService.unlock(req.getPeriod(), UID, req.getNote(),
                top.aole.vend.modules.basedata.interfaces.Operators.resolve(role));
        return R.ok();
    }

    @ApiOperation("上期调整聚合:book_period 内 biz≠book 的销售/单据(报表\"上期调整\"行取数口)")
    @GetMapping("/prior-adjust")
    public R<Map<String, Object>> priorAdjust(@RequestParam String bookPeriod) {
        return R.ok(periodLockService.priorAdjust(bookPeriod));
    }

    @ApiOperation("锁账记录列表")
    @GetMapping("/locks/list")
    public R<List<PeriodLock>> list() {
        return R.ok(periodLockService.listLocks());
    }

    @Data
    public static class LockReq {
        @NotBlank(message = "月份不能为空")
        private String period;
        private String note;
    }
}
