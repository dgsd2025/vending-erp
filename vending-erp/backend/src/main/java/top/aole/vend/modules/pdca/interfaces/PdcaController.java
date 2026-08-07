package top.aole.vend.modules.pdca.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.pdca.dto.PdcaDtos;
import top.aole.vend.modules.pdca.service.ActionItemService;
import top.aole.vend.modules.pdca.service.PdcaBoardService;
import top.aole.vend.modules.pdca.service.PdcaMetricService;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * PDCA 改进循环接口(M4-3,对照 mockup p11 / §8.4):
 * 六环节看板 + 改进任务 CRUD + 到期自动回查三分支 + 盘亏向导第 5 步一键起草 + 指标注册表。
 * user_id 暂 0L 占位(与全仓一致,SSO 后替换);操作人走 X-User-Name 占位头。
 */
@Api(tags = "PDCA · 改进循环(六环节看板/改进任务/到期回查)")
@RestController
@RequestMapping("/v1/pdca")
@RequiredArgsConstructor
public class PdcaController {

    private static final Long UID = 0L;

    private final ActionItemService actionItemService;
    private final PdcaBoardService boardService;
    private final PdcaMetricService metricService;

    // ============================== 看板 ==============================

    @ApiOperation("六环节 PDCA 看板:每环节本期 C 指标红绿灯(系统自动算)")
    @GetMapping("/board")
    public R<PdcaDtos.BoardResp> board() {
        return R.ok(boardService.board());
    }

    // ============================== 改进任务 CRUD ==============================

    @ApiOperation("改进任务清单(状态/环节/来源引用筛选;到期红标)")
    @GetMapping("/items")
    public R<List<PdcaDtos.ItemRow>> items(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String scene,
                                           @RequestParam(required = false) String refType,
                                           @RequestParam(required = false) Long refId) {
        return R.ok(actionItemService.list(status, scene, refType, refId));
    }

    @ApiOperation("驾驶舱卡:本周到期待验证的改进任务")
    @GetMapping("/items/due-week")
    public R<List<PdcaDtos.ItemRow>> dueWeek() {
        return R.ok(actionItemService.dueThisWeek());
    }

    @ApiOperation("登记改进任务(验证指标三件套:指标键+目标值+比较方向;登记时自动取基线值)")
    @PostMapping("/items")
    public R<Long> create(@Valid @RequestBody PdcaDtos.ItemSaveReq req,
                          @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return R.ok(actionItemService.create(req, UID, Operators.resolve(operator)));
    }

    @ApiOperation("修改改进任务(仅进行中/未见效可改;换指标自动重取基线)")
    @PutMapping("/items/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PdcaDtos.ItemSaveReq req,
                          @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        actionItemService.update(id, req, UID, Operators.resolve(operator));
        return R.ok(null);
    }

    @ApiOperation("手动关闭改进任务(必填原因留痕)")
    @PostMapping("/items/{id}/close")
    public R<Void> close(@PathVariable Long id, @RequestBody Map<String, String> body,
                         @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        actionItemService.close(id, body == null ? null : body.get("note"), UID, Operators.resolve(operator));
        return R.ok(null);
    }

    // ============================== 到期回查 ==============================

    @ApiOperation("单条一键回查:指标当前值 vs 目标 → 通过关闭 / 未达升级 / 需人工判定")
    @PostMapping("/items/{id}/recheck")
    public R<PdcaDtos.RecheckResp> recheck(@PathVariable Long id,
                                           @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return R.ok(actionItemService.recheck(id, Operators.resolve(operator)));
    }

    @ApiOperation("批量到期回查:验证日到了的进行中任务自动逐条判定(三分支汇总)")
    @PostMapping("/items/recheck-due")
    public R<PdcaDtos.RecheckBatchResp> recheckDue(
            @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return R.ok(actionItemService.recheckDue(Operators.resolve(operator)));
    }

    // ============================== 指标注册表 ==============================

    @ApiOperation("指标键注册表(新建任务下拉;每键一个取数函数,取不到的标需人工判定)")
    @GetMapping("/metrics")
    public R<List<PdcaDtos.MetricDefRow>> metrics() {
        return R.ok(metricService.listDefs());
    }

    @ApiOperation("指标当前值预览(登记前看基线;value=null 表示取不到=需人工判定)")
    @GetMapping("/metrics/value")
    public R<PdcaDtos.MetricValueResp> metricValue(@RequestParam String key,
                                                   @RequestParam(required = false) String param) {
        PdcaMetricService.Resolved r = metricService.resolve(key, param);
        PdcaDtos.MetricValueResp resp = new PdcaDtos.MetricValueResp();
        resp.setKey(key);
        resp.setParam(param);
        resp.setValue(r.value);
        PdcaMetricService.MetricDef def = metricService.def(key);
        resp.setUnit(def == null ? null : def.unit);
        resp.setNote(r.note);
        return R.ok(resp);
    }

    // ============================== 盘亏向导第 5 步 ==============================

    @ApiOperation("盘亏向导一键起草:按有改进空间的原因(吞货/过期/被盗)预填改进任务(不落库,确认后逐条 POST /items)")
    @GetMapping("/draft-from-stocktake/{stocktakeId}")
    public R<PdcaDtos.DraftResp> draftFromStocktake(@PathVariable Long stocktakeId) {
        return R.ok(actionItemService.draftFromStocktake(stocktakeId));
    }
}
