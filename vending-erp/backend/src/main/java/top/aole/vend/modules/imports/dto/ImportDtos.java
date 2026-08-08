package top.aole.vend.modules.imports.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入中心出入参集合。
 */
public final class ImportDtos {

    private ImportDtos() {
    }

    /** 上传解析结果:预览 + 列映射校验(两步式第①步,未入账) */
    @Data
    public static class PreviewResp {
        /** 确认口令:第②步 confirm 凭它取回暂存文件 */
        private String token;
        private String fileName;
        private String fileType;
        private int rowTotal;
        /** 列映射校验:每个期望列的命中情况 */
        private List<ColumnCheck> columnChecks = new ArrayList<>();
        /** 缺必填列时 false,前端禁用「确认导入」 */
        private boolean columnsOk;
        /** 文件里的原始表头 */
        private List<String> headers = new ArrayList<>();
        /** 前 20 行原样预览(表头名→值) */
        private List<Map<String, String>> previewRows = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class ColumnCheck {
        private String expected;
        private boolean required;
        private boolean found;

        public ColumnCheck() {
        }

        public ColumnCheck(String expected, boolean required, boolean found) {
            this.expected = expected;
            this.required = required;
            this.found = found;
        }
    }

    /** 导入自愈:AI 猜的单条列映射(期望列 ← 文件实际表头) */
    @Data
    public static class FixMapping {
        /** 系统期望的列名 */
        private String expected;
        private boolean required;
        /** AI/规则建议的文件实际表头(null=没猜到) */
        private String suggested;
        /** 该期望列的候选实际表头(按相似度排序,供人工下拉改) */
        private List<String> candidates = new ArrayList<>();
        /** 建议理由(人话) */
        private String reason;
    }

    /** 导入自愈建议结果(接入点#9 IMPORT_FIX) */
    @Data
    public static class FixSuggestResp {
        private String token;
        private List<FixMapping> mappings = new ArrayList<>();
        /** 置信分(已猜中的必填列 / 缺失必填列,规则真算) */
        private BigDecimal confidence;
        /** 落库的 LLM 调用 id(前端🔬透明四件套据此拉过程) */
        private Long llmCallId;
        /** 走了真模型还是 mock 断路 */
        private String mode;
    }

    /** 请求:让 AI 猜列映射 */
    @Data
    public static class FixSuggestReq {
        @NotBlank
        private String token;
        /** true=重新猜(跳过当日幂等缓存) */
        private boolean force;
    }

    /** 请求:按确认后的列映射(期望列→文件实际表头)重新校验预览 */
    @Data
    public static class FixApplyReq {
        @NotBlank
        private String token;
        /** 期望列名 → 文件实际表头 */
        @NotNull
        private Map<String, String> columnMap;
    }

    /** 第②步确认入参 */
    @Data
    public static class ConfirmReq {
        @NotBlank(message = "token 不能为空")
        private String token;
    }

    /** 导入完成报告 */
    @Data
    public static class CommitResp {
        private Long batchId;
        private String batchNo;
        private String fileType;
        private int rowTotal;
        private int rowOk;
        private int rowFail;
        private int rowDup;
        /** 待绑定行数(通道1=入库但 product_id 空;通道3=进 alias_pending 的行) */
        private int pendingBind;
        /** 改价待确认条数 */
        private int priceChangeCount;
        /** 通道2:生成转移单数 */
        private int docsCreated;
        /** 通道2:冲抵的手工预挂单数 */
        private int matchedPrePending;
        /** 通道2:落机器快照条数 */
        private int snapshots;
        /** 通道2:本次核销的 Pre-kit 配货单数(M2-3 afterImport 钩子) */
        private int prekitVerified;
        /** 负库存红灯(待补录采购) */
        private List<NegativeStock> negativeStock = new ArrayList<>();
    }

    @Data
    public static class NegativeStock {
        private Long productId;
        private String skuCode;
        private String productName;
        private BigDecimal balance;
    }

    /** 改价待确认项 */
    @Data
    public static class PriceChange {
        private Long productId;
        private String skuCode;
        private String productName;
        private BigDecimal refPrice;
        private BigDecimal newPrice;
        /** 侦测依据行数 */
        private long rowCount;
    }

    /** 改价确认入参 */
    @Data
    public static class PriceConfirmReq {
        @NotEmpty(message = "至少选择一条改价")
        private List<Item> items;

        @Data
        public static class Item {
            @NotNull
            private Long productId;
            @NotNull
            private BigDecimal newPrice;
        }
    }

    /** 回滚结果 */
    @Data
    public static class RollbackResp {
        private boolean success;
        /** 拒绝原因(已被下游引用时列出引用) */
        private List<String> blockers = new ArrayList<>();
        private int saleRemoved;
        private int docsVoided;
        private int ledgerRemoved;
        private int snapshotRemoved;
        /** 随回滚同事务作废的应付结算单数(M3-9 P0-3 应付链连锁) */
        private int settleBillsVoided;
    }

    /** 重处理待绑定行结果 */
    @Data
    public static class ReprocessResp {
        private int scanned;
        private int rebound;
        private int stillPending;
    }
}
