package top.aole.vend.modules.prekit.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.prekit.dto.PrekitDtos;
import top.aole.vend.modules.prekit.service.PrekitService;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Pre-kit 配货单 + 出库上架页(p5)接口(M2-3):
 * 补货建议勾选行 → 按机分组配货单 → 装箱执行 → 次日导入自动核销(带回率)。
 */
@Api(tags = "Pre-kit 配货单 · 出库上架(p5)")
@RestController
@RequestMapping("/v1/prekit")
@RequiredArgsConstructor
public class PrekitController {

    private final PrekitService prekitService;

    @ApiOperation("生成配货单:补货建议机器侧勾选行,按机分组,量可改(p2「生成配货单」按钮)")
    @PostMapping("/tickets/generate")
    public R<PrekitDtos.GenerateResp> generate(
            @Valid @RequestBody PrekitDtos.GenerateReq req,
            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(prekitService.generate(req, Operators.resolve(userName)));
    }

    @ApiOperation("配货单列表(状态 chip/带回率/超窗黄灯 overdue)")
    @GetMapping("/tickets")
    public R<List<Map<String, Object>>> tickets(@RequestParam(required = false) String status) {
        return R.ok(prekitService.listTickets(status));
    }

    @ApiOperation("配货单详情:按机打印友好视图 + FEFO 仓库最早入库日(先出旧货)")
    @GetMapping("/tickets/{id}")
    public R<Map<String, Object>> ticketDetail(@PathVariable Long id) {
        return R.ok(prekitService.ticketDetail(id));
    }

    @ApiOperation("标记已执行(仓库装箱出发,记执行人/时间;建议行联动 → 已执行)")
    @PostMapping("/tickets/{id}/execute")
    public R<Void> execute(@PathVariable Long id,
                           @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        prekitService.execute(id, Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("手动核销(修复口:超窗补导后扫 ±48h 窗口重算)")
    @PostMapping("/tickets/{id}/verify")
    public R<Map<String, Object>> verify(@PathVariable Long id,
                                         @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(prekitService.verifyManually(id, Operators.resolve(userName)));
    }

    @ApiOperation("改带出量(仅「已生成」可改)")
    @PutMapping("/items/{itemId}")
    public R<Void> updateItemQty(@PathVariable Long itemId,
                                 @Valid @RequestBody PrekitDtos.ItemQtyReq req,
                                 @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        prekitService.updateItemQty(itemId, req.getQtyPlanned(), Operators.resolve(userName));
        return R.ok();
    }

    @ApiOperation("转移单列表(出库上架/退库;来源=导入/手工,预挂单/已冲抵可见)")
    @GetMapping("/transfers")
    public R<List<Map<String, Object>>> transfers() {
        return R.ok(prekitService.listTransfers());
    }

    @ApiOperation("手工转移单录入(兜底):出库上架确认后=预挂单,待次日导入自动冲抵")
    @PostMapping("/transfers")
    public R<Map<String, Object>> createManualTransfer(
            @Valid @RequestBody PrekitDtos.ManualTransferReq req,
            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(prekitService.createManualTransfer(req, Operators.resolve(userName)));
    }
}
