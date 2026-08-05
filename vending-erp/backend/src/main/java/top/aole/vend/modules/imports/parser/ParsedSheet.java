package top.aole.vend.modules.imports.parser;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析结果:表头 + 行(每行 = 表头名→单元格文本)。
 * 行号 rowNo 为原 Excel 行号(1-based,含表头行偏移),错误报告直接引用。
 */
@Data
public class ParsedSheet {

    private List<String> headers = new ArrayList<>();
    private List<Row> rows = new ArrayList<>();

    @Data
    public static class Row {
        /** 原文件行号(1-based) */
        private int rowNo;
        /** 表头名 → 单元格文本(数字/日期已规整为字符串;空单元格为 null) */
        private Map<String, String> cells = new LinkedHashMap<>();

        public String get(String header) {
            String v = cells.get(header);
            return v == null || v.trim().isEmpty() ? null : v.trim();
        }
    }
}
