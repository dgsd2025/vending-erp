package top.aole.vend.modules.monthly.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.monthly.dto.MonthlyDtos;
import top.aole.vend.modules.monthly.service.MonthlyAnalysisService;
import top.aole.vend.modules.monthly.service.MonthlyCalendarService;
import top.aole.vend.modules.monthly.service.MonthlyExportService;
import top.aole.vend.modules.monthly.service.MonthlyReportService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 月度报表包接口(M4-4,§10.3 七件套 + §10.2 财务工作日历 + 一键导出)。
 * 全部只读聚合;导出走后端生成流(Excel/Word),前端下载。
 */
@Api(tags = "月度报表 · 七件套/导出/工作日历")
@RestController
@RequestMapping("/v1/monthly")
@RequiredArgsConstructor
public class MonthlyController {

    private final MonthlyReportService monthlyReportService;
    private final MonthlyAnalysisService monthlyAnalysisService;
    private final MonthlyCalendarService monthlyCalendarService;
    private final MonthlyExportService monthlyExportService;

    @ApiOperation("报表包总览(前六件套:进销存/利润表/现金流水/往来/损耗/资产快照);month 空取最近有数月")
    @GetMapping("/package")
    public R<MonthlyDtos.PackageResp> pkg(@RequestParam(required = false) String month) {
        return R.ok(monthlyReportService.buildPackage(month));
    }

    @ApiOperation("第七件《月度经营分析报告》:固定七节,规则出数字(带溯源)+ AI mock 起草综述(🔬 四件套);force 重新起草")
    @GetMapping("/analysis")
    public R<MonthlyDtos.AnalysisReport> analysis(@RequestParam(required = false) String month,
                                                  @RequestParam(defaultValue = "false") boolean force) {
        return R.ok(monthlyAnalysisService.analyze(month, force));
    }

    @ApiOperation("财务月度工作日历(§10.2):1-5 日各步骤自动完成/待人工状态看板")
    @GetMapping("/calendar")
    public R<MonthlyDtos.CalendarBoard> calendar(@RequestParam(required = false) String month) {
        return R.ok(monthlyCalendarService.board(month));
    }

    @ApiOperation("导出 Excel:前六件套各一 sheet(POI XSSF)")
    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel(@RequestParam(required = false) String month) {
        byte[] data = monthlyExportService.exportExcel(month);
        return download(data, "月度报表包-" + safeMonth(month) + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @ApiOperation("导出 Word:第七件《月度经营分析报告》(POI XWPF,含表格)")
    @GetMapping("/export/word")
    public ResponseEntity<byte[]> exportWord(@RequestParam(required = false) String month) {
        byte[] data = monthlyExportService.exportWord(month);
        return download(data, "月度经营分析报告-" + safeMonth(month) + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private ResponseEntity<byte[]> download(byte[] data, String filename, String contentType) {
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            encoded = filename;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        // filename* 兼容中文文件名
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"report\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(data.length);
        return new ResponseEntity<>(data, headers, org.springframework.http.HttpStatus.OK);
    }

    private String safeMonth(String month) {
        return month == null || month.isEmpty() ? "latest" : month;
    }
}
