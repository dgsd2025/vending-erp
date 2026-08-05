package top.aole.vend.modules.imports.parser;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 原始行解析结果(按列索引取值,期初向导用):
 * 老 Excel 套表存在重复表头,不能按表头名建 Map(会互相覆盖)。
 */
@Data
public class RawSheet {

    private String sheetName;
    private List<RawRow> rows = new ArrayList<>();

    /** 数据行(含表头行,调用方自行跳过) */
    @Data
    public static class RawRow {
        /** 原文件行号(1-based) */
        private int rowNo;
        /** 按列索引的单元格文本(空=null),已做数字/日期规整 */
        private List<String> cells = new ArrayList<>();

        public String get(int index) {
            return index >= 0 && index < cells.size() ? cells.get(index) : null;
        }
    }
}
