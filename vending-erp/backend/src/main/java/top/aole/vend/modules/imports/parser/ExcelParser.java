package top.aole.vend.modules.imports.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import top.aole.vend.common.exception.BizException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * xlsx 解析器(POI):读第一个 sheet,第一非空行当表头,输出规整文本。
 * 规整规则:数字去掉浮点尾巴(3.0→3);日期统一 yyyy-MM-dd HH:mm:ss;公式取缓存值。
 * fanmaiji 导出常见脏点已兜:文本型日期、纯数字条码被 Excel 存成 double。
 */
@Component
public class ExcelParser {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DataFormatter formatter = new DataFormatter();

    public ParsedSheet parse(InputStream in) {
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            ParsedSheet result = new ParsedSheet();
            int headerRowIdx = -1;
            Map<Integer, String> colIndex = new HashMap<>();
            for (Row row : sheet) {
                if (headerRowIdx < 0) {
                    // 第一非空行 = 表头
                    boolean any = false;
                    for (Cell cell : row) {
                        String text = cellText(cell);
                        if (text != null && !text.trim().isEmpty()) {
                            any = true;
                            colIndex.put(cell.getColumnIndex(), text.trim());
                        }
                    }
                    if (any) {
                        headerRowIdx = row.getRowNum();
                        result.getHeaders().addAll(colIndex.values());
                    }
                    continue;
                }
                ParsedSheet.Row parsed = new ParsedSheet.Row();
                parsed.setRowNo(row.getRowNum() + 1);
                boolean any = false;
                for (Map.Entry<Integer, String> e : colIndex.entrySet()) {
                    Cell cell = row.getCell(e.getKey());
                    String text = cellText(cell);
                    if (text != null && !text.trim().isEmpty()) {
                        any = true;
                        parsed.getCells().put(e.getValue(), text.trim());
                    } else {
                        parsed.getCells().put(e.getValue(), null);
                    }
                }
                if (any) {
                    result.getRows().add(parsed);
                }
            }
            if (headerRowIdx < 0) {
                throw new BizException("文件内容为空:找不到表头行");
            }
            return result;
        } catch (IOException e) {
            throw new BizException("Excel 解析失败(仅支持 .xlsx):" + e.getMessage());
        }
    }

    /**
     * 期初向导专用:按 sheet 名(模糊包含)取整表原始行,索引取值。
     * 老 Excel 套表存在重复表头(采购入库表两列「商品编码」、配比底稿两对「归集编码」),
     * 表头名→值 的 Map 会互相覆盖,必须按列索引读(M1-6)。
     */
    public RawSheet parseRaw(InputStream in, String sheetNameContains) {
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                String name = wb.getSheetName(i);
                if (name != null && name.contains(sheetNameContains)) {
                    sheet = wb.getSheetAt(i);
                    break;
                }
            }
            if (sheet == null) {
                throw new BizException("文件里找不到工作表「" + sheetNameContains + "」——请上传老 Excel 进销存套表原文件");
            }
            RawSheet result = new RawSheet();
            result.setSheetName(sheet.getSheetName());
            for (Row row : sheet) {
                RawSheet.RawRow parsed = new RawSheet.RawRow();
                parsed.setRowNo(row.getRowNum() + 1);
                short lastCell = row.getLastCellNum();
                boolean any = false;
                for (int c = 0; c < lastCell; c++) {
                    String text = cellText(row.getCell(c));
                    text = text == null || text.trim().isEmpty() ? null : text.trim();
                    parsed.getCells().add(text);
                    if (text != null) {
                        any = true;
                    }
                }
                if (any) {
                    result.getRows().add(parsed);
                }
            }
            if (result.getRows().isEmpty()) {
                throw new BizException("工作表「" + sheet.getSheetName() + "」内容为空");
            }
            return result;
        } catch (IOException e) {
            throw new BizException("Excel 解析失败(仅支持 .xlsx):" + e.getMessage());
        }
    }

    /** 单元格 → 规整文本 */
    private String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        switch (type) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime dt = cell.getLocalDateTimeCellValue();
                    return dt == null ? null : dt.format(DATE_TIME);
                }
                // 条码/订单号被存成数字:去科学计数法与 .0 尾巴
                return BigDecimal.valueOf(cell.getNumericCellValue())
                        .stripTrailingZeros().toPlainString();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case STRING:
                return cell.getStringCellValue();
            case BLANK:
                return null;
            default:
                return formatter.formatCellValue(cell);
        }
    }
}
