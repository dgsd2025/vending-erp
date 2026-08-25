package top.aole.vend.modules.basedata.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.ProductImportService;
import top.aole.vend.modules.basedata.application.ProductService;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos;

import javax.validation.Valid;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 商品档案接口。完整路径 /api/v1/basedata/products。
 * 铁律:停售≠删除,有流水的永不删——因此没有 DELETE 接口,只有状态流转。
 */
@Api(tags = "基础档案 · 商品")
@RestController
@RequestMapping("/v1/basedata/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductImportService productImportService;

    @ApiOperation("分页列表(名称/编码/条码关键字 + 分类 + 状态)")
    @GetMapping
    public R<Page<Product>> page(@RequestParam(defaultValue = "1") long current,
                                 @RequestParam(defaultValue = "20") long size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(required = false) String productStatus) {
        return R.ok(productService.page(current, size, keyword, category, productStatus));
    }

    @ApiOperation("详情")
    @GetMapping("/{id}")
    public R<Product> detail(@PathVariable Long id) {
        return R.ok(productService.getById(id));
    }

    @ApiOperation("新建")
    @PostMapping
    public R<Product> create(@RequestBody Product product,
                             @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(productService.create(product, Operators.resolve(userName)));
    }

    @ApiOperation("编辑(改售价会自动写 price_log)")
    @PutMapping("/{id}")
    public R<Product> update(@PathVariable Long id, @RequestBody Product product,
                             @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(productService.update(id, product, Operators.resolve(userName)));
    }

    // ---------- 建档导入(设置中心 → 商品 → 导入商品列表) ----------

    @ApiOperation("导入①:上传附件即刻解析成可编辑的商品行(不落库)")
    @PostMapping("/import/parse")
    public R<ProductImportDtos.ParseResp> importParse(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("没选文件");
        }
        try {
            return R.ok(productImportService.parse(file.getOriginalFilename(), file.getBytes()));
        } catch (IOException e) {
            throw new BizException("读文件失败:" + e.getMessage());
        }
    }

    @ApiOperation("导入②:按列表内容入档(新建/更新 + 绑后台别名 + 消待绑队列)")
    @PostMapping("/import/commit")
    public R<ProductImportDtos.CommitResp> importCommit(@Valid @RequestBody ProductImportDtos.CommitReq req,
                                                        @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(productImportService.commit(req.getRows(), Operators.resolve(userName)));
    }

    @ApiOperation("下载导入模板(xlsx,表头即解析口径)")
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] data = productImportService.template();
        String filename = "商品导入模板.xlsx";
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            encoded = "product-import-template.xlsx";
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"product-import-template.xlsx\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(data.length);
        return new ResponseEntity<>(data, headers, HttpStatus.OK);
    }

    @ApiOperation("状态流转:在售/清仓中/停售")
    @PutMapping("/{id}/status")
    public R<Product> changeStatus(@PathVariable Long id, @Valid @RequestBody Dtos.StatusReq req,
                                   @RequestHeader(value = Operators.HEADER, required = false) String userName) {
        return R.ok(productService.changeStatus(id, req.getTargetStatus(), Operators.resolve(userName)));
    }
}
