package top.aole.vend.modules.money.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.AccountService;
import top.aole.vend.modules.money.service.AttachmentService;
import top.aole.vend.modules.money.service.CashFlowQueryService;
import top.aole.vend.modules.money.service.SettleModeService;

import javax.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.util.List;

/**
 * 钱账基座接口(M3-1):账户 CRUD / 流水只读 / 凭证通用件 / 结算模式。
 *
 * 注意:**没有"新增流水"接口**——cash_flow 唯一写手是包私有 CashFlowWriter,
 * 只接受单据确认事件(MoneyPostingEvent);钱只能被单据改(§9.1)。
 * user_id 暂 0L 占位(与 doc/stocktake 一致,SSO 后替换);经手人走 X-User-Name 头。
 */
@Api(tags = "钱账基座 · 账户/流水/凭证/结算模式")
@RestController
@RequestMapping("/v1/money")
@RequiredArgsConstructor
public class MoneyController {

    private static final Long UID = 0L;

    private final AccountService accountService;
    private final CashFlowQueryService cashFlowQueryService;
    private final AttachmentService attachmentService;
    private final SettleModeService settleModeService;

    // ============================== 账户 ==============================

    @ApiOperation("账户列表(带实时余额=期初+Σ流水;虚拟账户排后)")
    @GetMapping("/accounts")
    public R<List<MoneyDtos.AccountRow>> listAccounts() {
        return R.ok(accountService.list());
    }

    @ApiOperation("新建账户(真实:微信/支付宝/银行卡/现金;虚拟:平台待结算/老板垫付;可顺带设期初)")
    @PostMapping("/accounts")
    public R<Long> createAccount(@Valid @RequestBody MoneyDtos.AccountCreateReq req,
                                 @RequestHeader(value = Operators.HEADER, required = false) String op) {
        return R.ok(accountService.create(req, UID, Operators.resolve(op)));
    }

    @ApiOperation("账户改名(类型/期初不可改)")
    @PutMapping("/accounts/{id}")
    public R<Void> updateAccount(@PathVariable Long id, @Valid @RequestBody MoneyDtos.AccountUpdateReq req,
                                 @RequestHeader(value = Operators.HEADER, required = false) String op) {
        accountService.update(id, req, UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("设置期初余额(只能设一次!再改必须走资金调整单 M3-5)")
    @PostMapping("/accounts/{id}/opening")
    public R<Void> setOpening(@PathVariable Long id, @Valid @RequestBody MoneyDtos.OpeningReq req,
                              @RequestHeader(value = Operators.HEADER, required = false) String op) {
        accountService.setOpening(id, req.getOpeningBalance(), UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("启用/停用账户(有流水永不删,只停用)")
    @PutMapping("/accounts/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody MoneyDtos.StatusReq req,
                                @RequestHeader(value = Operators.HEADER, required = false) String op) {
        accountService.changeStatus(id, req.getTargetStatus(), UID, Operators.resolve(op));
        return R.ok(null);
    }

    @ApiOperation("账户实时余额(期初+Σ流水)")
    @GetMapping("/accounts/{id}/balance")
    public R<BigDecimal> balance(@PathVariable Long id) {
        return R.ok(accountService.balanceOf(id));
    }

    // ============================== 流水(只读) ==============================

    @ApiOperation("流水分页(账户/类别/入账月区间筛选;无写接口,流水只由单据事件产生)")
    @GetMapping("/flows")
    public R<Page<MoneyDtos.FlowRow>> pageFlows(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "20") long size,
                                                @RequestParam(required = false) Long accountId,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String fromPeriod,
                                                @RequestParam(required = false) String toPeriod) {
        return R.ok(cashFlowQueryService.page(current, size, accountId, category, fromPeriod, toPeriod));
    }

    @ApiOperation("pl_line×月聚合(利润表取数口,M3 报表票直接用)")
    @GetMapping("/flows/pl-summary")
    public R<List<MoneyDtos.PlSummaryRow>> plSummary(@RequestParam(required = false) String fromPeriod,
                                                     @RequestParam(required = false) String toPeriod) {
        return R.ok(cashFlowQueryService.plMonthlySummary(fromPeriod, toPeriod));
    }

    // ============================== 凭证通用件 ==============================

    @ApiOperation("上传凭证并关联单据(服务端生成归档名防路径穿越;类型:转账截图/平台账单/赔付凭证/发票/照片)")
    @PostMapping("/attachments")
    public R<Long> uploadAttachment(@RequestParam String refType,
                                    @RequestParam Long refId,
                                    @RequestParam String attType,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestHeader(value = Operators.HEADER, required = false) String op)
            throws IOException {
        return R.ok(attachmentService.upload(refType, refId, attType,
                file.getOriginalFilename(), file.getBytes(), UID, Operators.resolve(op)));
    }

    @ApiOperation("某单据的凭证列表")
    @GetMapping("/attachments")
    public R<List<MoneyDtos.AttachmentRow>> listAttachments(@RequestParam String refType,
                                                            @RequestParam Long refId) {
        return R.ok(attachmentService.listOf(refType, refId));
    }

    @ApiOperation("凭证预览/下载(inline 输出,图片/PDF 浏览器直显)")
    @GetMapping("/attachments/{id}/file")
    public ResponseEntity<byte[]> previewAttachment(@PathVariable Long id) throws IOException {
        AttachmentService.Preview preview = attachmentService.load(id);
        String encoded = URLEncoder.encode(preview.attachment.getFileName() == null
                ? "attachment" : preview.attachment.getFileName(), "UTF-8").replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(preview.contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(preview.content);
    }

    // ============================== 结算模式 ==============================

    @ApiOperation("查询结算模式(UNSET 时带待核实横幅文案 + 三档说明)")
    @GetMapping("/settle-mode")
    public R<MoneyDtos.SettleModeResp> getSettleMode() {
        return R.ok(settleModeService.get());
    }

    @ApiOperation("设置结算模式(限老板角色 X-User-Role,M3-9 P1-7;核实真实交易后一键定型;op_log 留痕)")
    @PutMapping("/settle-mode")
    public R<MoneyDtos.SettleModeResp> setSettleMode(@Valid @RequestBody MoneyDtos.SettleModeReq req,
                                                     @RequestHeader(value = Operators.HEADER, required = false) String op,
                                                     @RequestHeader(value = "X-User-Role", required = false) String role) {
        settleModeService.set(req.getMode(), UID, Operators.resolve(op), Operators.resolve(role));
        return R.ok(settleModeService.get());
    }
}
