package top.aole.vend.modules.imports.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.domain.entity.ImportError;
import top.aole.vend.modules.imports.service.ImportService;

import javax.validation.Valid;
import java.io.IOException;
import java.util.List;

/**
 * 导入中心接口(M1-3):三通道两步式(上传预览 → 确认入账)+ 批次/错误/回滚/重处理/改价。
 */
@Api(tags = "导入中心 · 三通道")
@RestController
@RequestMapping("/v1/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @ApiOperation("第①步:上传解析预览(前20行+列映射校验,未入账)")
    @PostMapping("/upload")
    public R<ImportDtos.PreviewResp> upload(@RequestParam("fileType") String fileType,
                                            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException("文件为空");
        }
        try {
            return R.ok(importService.upload(fileType, file.getOriginalFilename(), file.getBytes()));
        } catch (IOException e) {
            throw new BizException("读文件失败:" + e.getMessage());
        }
    }

    @ApiOperation("第②步:确认入账(按 token 取回暂存文件,真正处理)")
    @PostMapping("/confirm")
    public R<ImportDtos.CommitResp> confirm(@Valid @RequestBody ImportDtos.ConfirmReq req,
                                            @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(importService.confirm(req.getToken(), Operators.resolve(userName)));
    }

    @ApiOperation("批次历史分页")
    @GetMapping("/batches")
    public R<Page<ImportBatch>> batches(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String fileType) {
        return R.ok(importService.pageBatches(current, size, fileType));
    }

    @ApiOperation("批次行级错误分页")
    @GetMapping("/batches/{id}/errors")
    public R<Page<ImportError>> errors(@PathVariable Long id,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "50") long size) {
        return R.ok(importService.pageErrors(id, current, size));
    }

    @ApiOperation("整批回滚(下游已引用则拒绝并列出引用)")
    @PostMapping("/batches/{id}/rollback")
    public R<ImportDtos.RollbackResp> rollback(@PathVariable Long id,
                                               @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(importService.rollback(id, Operators.resolve(userName)));
    }

    @ApiOperation("重处理该批待绑定行(绑定后回补 product_id + 成本)")
    @PostMapping("/batches/{id}/reprocess")
    public R<ImportDtos.ReprocessResp> reprocess(@PathVariable Long id,
                                                 @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(importService.reprocessPending(id, Operators.resolve(userName)));
    }

    @ApiOperation("改价待确认清单(行成交价≠档案参考价)")
    @GetMapping("/batches/{id}/price-changes")
    public R<List<ImportDtos.PriceChange>> priceChanges(@PathVariable Long id) {
        return R.ok(importService.listPriceChanges(id));
    }

    @ApiOperation("确认改价:更新档案参考价+写 price_log")
    @PostMapping("/batches/{id}/price-changes/confirm")
    public R<Integer> confirmPriceChanges(@PathVariable Long id,
                                          @Valid @RequestBody ImportDtos.PriceConfirmReq req,
                                          @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(importService.confirmPriceChanges(id, req, Operators.resolve(userName)));
    }
}
