package top.aole.vend.modules.basedata.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品建档导入(附件上传 → 自动解析 → 落到列表 → 确认入档)的请求/响应体。
 * 数字列一律用 String 传:解析结果要落到前端可编辑的表格里,用户改成什么样都先原样收下,
 * 校验与转数字统一在服务端做(前端 NumberField 的空串/半截输入不会变成 NaN 打回去)。
 */
public final class ProductImportDtos {

    private ProductImportDtos() {
    }

    /** 行动作 */
    public static final String ACTION_CREATE = "新建";
    public static final String ACTION_UPDATE = "更新";
    public static final String ACTION_ERROR = "错误";

    /** 解析出来的一行;前端可就地编辑后原样回传 */
    @Data
    public static class Row {
        /** Excel 里的行号(报错定位用) */
        private Integer rowNo;
        private String skuCode;
        private String productName;
        private String barcode;
        private String category;
        private String unit;
        private String boxSpec;
        private String shelfLifeDays;
        private String refCost;
        private String refPrice;
        private String minDisplayQty;
        private String remark;
        /** 新建 / 更新 / 错误(服务端提交时会按当时的档案重算,不信前端传来的值) */
        private String action;
        /** action=错误 时的原因 */
        private String errorMsg;
        /** 已存在时回填,供前端显示"更新哪一条";提交仍以 sku_code 为准 */
        private Long existingProductId;
    }

    /** 解析结果:一次把「有多少行、几条新建、几条更新、哪行有问题」全给出来 */
    @Data
    public static class ParseResp {
        private String fileName;
        private int rowTotal;
        private int createCount;
        private int updateCount;
        private int errorCount;
        /** 这批档案建完能顺带消掉的「待绑别名」条数(预估,提交时以实际匹配为准) */
        private int pendingHitCount;
        private List<String> headers = new ArrayList<>();
        /** 表头识别情况等提示,不阻断导入 */
        private List<String> warnings = new ArrayList<>();
        private List<Row> rows = new ArrayList<>();
    }

    @Data
    public static class CommitReq {
        @NotEmpty(message = "没有可导入的行")
        private List<Row> rows;
    }

    @Data
    public static class CommitResp {
        private int created;
        private int updated;
        private int failed;
        /** 建档同时绑上的后台别名条数(编号+条码) */
        private int aliasBound;
        /** 顺带从「待绑别名」队列里消掉的条数 */
        private int pendingCleared;
        private List<RowError> errors = new ArrayList<>();
    }

    @Data
    @AllArgsConstructor
    public static class RowError {
        private Integer rowNo;
        private String skuCode;
        private String message;
    }
}
