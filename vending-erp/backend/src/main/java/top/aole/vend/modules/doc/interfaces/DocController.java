package top.aole.vend.modules.doc.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.dto.CostAdjustReq;
import top.aole.vend.modules.doc.dto.RedFlushReq;
import top.aole.vend.modules.doc.service.CostAdjustService;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.doc.service.RedFlushService;

import javax.validation.Valid;

/**
 * 单据通用接口(M1-7):详情 / 红冲影响清单+执行 / 成本调整预览+执行。
 * user_id 暂 0L 占位(与 purchase 模块一致,SSO 后替换)。
 */
@Api(tags = "单据 · 红冲/成本调整")
@RestController
@RequestMapping("/v1/docs")
@RequiredArgsConstructor
public class DocController {

    private static final Long UID = 0L;

    private final DocService docService;
    private final RedFlushService redFlushService;
    private final CostAdjustService costAdjustService;

    @ApiOperation("单据列表(按类型/状态过滤)")
    @GetMapping
    public R<Object> list(@RequestParam(required = false) DocType docType,
                          @RequestParam(required = false) DocStatus docStatus) {
        return R.ok(docService.listDocs(docType, docStatus));
    }

    @ApiOperation("单据详情(头+明细)")
    @GetMapping("/{id}")
    public R<DocService.DocDetail> detail(@PathVariable Long id) {
        return R.ok(docService.getDoc(id));
    }

    // ============================== 红冲(P0-1) ==============================

    @ApiOperation("红冲影响清单(确认页取数):下游检查+库存/应付/毛利影响,executable=false 列拦截原因")
    @GetMapping("/{id}/red-flush/preview")
    public R<RedFlushService.Preview> redFlushPreview(@PathVariable Long id) {
        return R.ok(redFlushService.preview(id));
    }

    @ApiOperation("执行整单红冲:生成等额反向单(免负库存拦截)→原单已红冲;锁账期需 bossOverride+老板角色头+备注")
    @PostMapping("/{id}/red-flush")
    public R<Long> redFlush(@PathVariable Long id, @Valid @RequestBody RedFlushReq req,
                            @RequestHeader(value = "X-User-Role", required = false) String role) {
        // P1-2:老板越权与解锁同一把尺子——读 X-User-Role 占位角色头交 service 校验(SSO 后统一替换)
        return R.ok(redFlushService.execute(id, UID, req.getReason(), req.isBossOverride(),
                top.aole.vend.modules.basedata.interfaces.Operators.resolve(role)));
    }

    // ============================== 成本调整(P0-1 单价错) ==============================

    @ApiOperation("成本调整预览:Δ单价×未售/已售切分(未售→ledger qty=0行;已售→备注+pl_line)")
    @PostMapping("/{id}/cost-adjust/preview")
    public R<CostAdjustService.Preview> costAdjustPreview(@PathVariable Long id,
                                                          @Valid @RequestBody CostAdjustReq req) {
        return R.ok(costAdjustService.preview(id, req));
    }

    @ApiOperation("执行成本调整:生成 doc_type=成本调整 单并确认过账;锁账期需 bossOverride+老板角色头+备注")
    @PostMapping("/{id}/cost-adjust")
    public R<Long> costAdjust(@PathVariable Long id, @Valid @RequestBody CostAdjustReq req,
                              @RequestHeader(value = "X-User-Role", required = false) String role) {
        return R.ok(costAdjustService.execute(id, req, UID,
                top.aole.vend.modules.basedata.interfaces.Operators.resolve(role)));
    }
}
