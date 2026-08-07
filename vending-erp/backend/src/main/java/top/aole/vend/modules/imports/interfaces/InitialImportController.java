package top.aole.vend.modules.imports.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.imports.dto.InitialDtos;
import top.aole.vend.modules.imports.service.InitialImportService;

import javax.validation.Valid;
import java.io.IOException;

/**
 * 期初导入向导接口(M1-6):三步(商品别名/历史采购/历史销售)+ 状态 + 对平校验。
 * 三步都直接吃老 Excel 进销存套表原文件(按 sheet 名取表,不用改造文件)。
 */
@Api(tags = "导入中心 · 期初向导")
@RestController
@RequestMapping("/v1/imports/initial")
@RequiredArgsConstructor
public class InitialImportController {

    private final InitialImportService initialImportService;

    @ApiOperation("向导状态:三步完成情况 + 系统总采购/销售额")
    @GetMapping("/status")
    public R<InitialDtos.StatusResp> status() {
        return R.ok(initialImportService.status());
    }

    @ApiOperation("第①步上传:商品档案+配比底稿(检出一码多品冲突组)")
    @PostMapping("/step1/upload")
    public R<InitialDtos.Step1PreviewResp> step1Upload(@RequestParam("file") MultipartFile file) {
        return R.ok(initialImportService.step1Upload(file.getOriginalFilename(), bytes(file)));
    }

    @ApiOperation("第①步确认:建商品/机器/别名(冲突组必须带处理方案,未处理整批不放行)")
    @PostMapping("/step1/confirm")
    public R<InitialDtos.Step1Resp> step1Confirm(
            @Valid @RequestBody InitialDtos.Step1ConfirmReq req,
            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(initialImportService.step1Confirm(req, Operators.resolve(userName)));
    }

    @ApiOperation("第②步上传:历史采购(采购入库表)预览")
    @PostMapping("/step2/upload")
    public R<InitialDtos.Step2PreviewResp> step2Upload(@RequestParam("file") MultipartFile file) {
        return R.ok(initialImportService.step2Upload(file.getOriginalFilename(), bytes(file)));
    }

    @ApiOperation("第②步确认:按入库日+供应商生成期初单并过账(建立加权成本历史)")
    @PostMapping("/step2/confirm")
    public R<InitialDtos.Step2Resp> step2Confirm(
            @RequestParam("token") String token,
            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(initialImportService.step2Confirm(token, Operators.resolve(userName)));
    }

    @ApiOperation("第③步上传:历史销售(销售明细表)预览")
    @PostMapping("/step3/upload")
    public R<InitialDtos.Step3PreviewResp> step3Upload(@RequestParam("file") MultipartFile file) {
        return R.ok(initialImportService.step3Upload(file.getOriginalFilename(), bytes(file)));
    }

    @ApiOperation("第③步确认:复用通道1入 sale_record(订单号去重幂等)")
    @PostMapping("/step3/confirm")
    public R<ImportDtos.CommitResp> step3Confirm(
            @RequestParam("token") String token,
            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(initialImportService.step3Confirm(token, Operators.resolve(userName)));
    }

    @ApiOperation("对平校验:系统总采购/销售额 vs 老账数字(±0.5 元),双过=期初完成")
    @PostMapping("/validate")
    public R<InitialDtos.ValidateResp> validate(
            @Valid @RequestBody InitialDtos.ValidateReq req,
            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(initialImportService.validate(req, Operators.resolve(userName)));
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("文件为空");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BizException("读文件失败:" + e.getMessage());
        }
    }
}
