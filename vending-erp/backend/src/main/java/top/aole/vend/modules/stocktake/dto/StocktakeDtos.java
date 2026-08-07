package top.aole.vend.modules.stocktake.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 盘点模块 DTO 集(M2-4)。
 */
public final class StocktakeDtos {

    private StocktakeDtos() {
    }

    // ============================== 入参 ==============================

    /** 新建盘点单:选范围 → 系统快照账面数 */
    @Data
    public static class CreateReq {
        /** 仓库/机器 */
        @NotBlank(message = "盘点范围不能为空(仓库/机器)")
        private String scopeType;
        /** scope=机器时必填 */
        private Long machineId;
        /** 来源:月度SOP任务包/补货顺手盘/手动(默认手动) */
        private String sourceTask;
    }

    /** 录入实盘:只提交有差异的行,整包替换(未提交的行=视同相符,自动复位) */
    @Data
    public static class SaveItemsReq {
        @Valid
        @NotNull(message = "差异行列表不能为空(全部相符请传空数组)")
        private List<ItemReq> rows = new ArrayList<>();
    }

    @Data
    public static class ItemReq {
        @NotNull(message = "商品不能为空")
        private Long productId;
        @NotNull(message = "实盘数不能为空")
        private BigDecimal actualQty;
        /** 差异行必选:吞货掉货/过期报损/录入错误/被盗/盘点错误/原因不明 */
        private String diffReason;
        /** 线下销售差异豁免(P2-13):该行差异由线下补录销售造成,不算损耗 */
        private Boolean offlineExempt;
    }

    /** 确认盘点(五步向导第 3 步):生成盘盈/盘亏单 + 机器锚点 */
    @Data
    public static class ConfirmReq {
        /** 锁账期老板越权(占位,同红冲一把尺子) */
        private boolean bossOverride;
        /** 越权备注(越权时强制) */
        private String note;
    }

    // ============================== 出参 ==============================

    /** 盘点单列表行(带机器名+差异汇总) */
    @Data
    public static class ListRow {
        private Long id;
        private String stNo;
        private String scopeType;
        private Long machineId;
        private String machineName;
        private LocalDateTime snapshotTime;
        private String stStatus;
        private Long gainDocId;
        private Long lossDocId;
        private String sourceTask;
        private Long diffCount;
        private BigDecimal diffQty;
        private BigDecimal diffAmount;
    }

    /** 盘点明细行(带商品名) */
    @Data
    public static class ItemRow {
        private Long id;
        private Long productId;
        private String skuCode;
        private String productName;
        private String slotNo;
        private BigDecimal bookQty;
        private BigDecimal actualQty;
        private BigDecimal diffQty;
        private BigDecimal diffAmount;
        private String diffReason;
        private Boolean offlineExempt;
    }

    /** 盘点单详情 */
    @Data
    public static class DetailResp {
        private Long id;
        private String stNo;
        private String scopeType;
        private Long machineId;
        private String machineName;
        private LocalDateTime snapshotTime;
        private String stStatus;
        private Long gainDocId;
        private Long lossDocId;
        private String sourceTask;
        private List<ItemRow> items;
        /** 差异行数(diff≠0) */
        private long diffCount;
    }

    /** 五步向导第 1 步:系统自动查账(是不是账记错了) */
    @Data
    public static class PrecheckResp {
        /** 近 7 天导入批次(漏导排查) */
        private List<ImportBatchRow> imports7d = new ArrayList<>();
        /** 近 7 天影响本范围的单据变更 */
        private List<DocRow> docs7d = new ArrayList<>();
        /** 机器范围:近 7 天线下补录销售(机器账不含线下出货 → 可豁免) */
        private List<OfflineSaleRow> offlineSales = new ArrayList<>();
        /** "可能漏导"提示清单 */
        private List<String> hints = new ArrayList<>();
    }

    @Data
    public static class ImportBatchRow {
        private Long id;
        private String batchNo;
        private String fileType;
        private String batchStatus;
        private String periodRange;
        private Integer rowOk;
        private LocalDateTime createTime;
    }

    @Data
    public static class DocRow {
        private Long id;
        private String docNo;
        private String docType;
        private String docStatus;
        private String docSource;
        private LocalDate bizDate;
        private BigDecimal totalQty;
    }

    @Data
    public static class OfflineSaleRow {
        private Long productId;
        private String productName;
        private Long cnt;
        private BigDecimal qty;
    }

    /** 确认结果 */
    @Data
    public static class ConfirmResp {
        private Long stocktakeId;
        /** 盘盈入库单(仓库范围) */
        private Long gainDocId;
        /** 盘亏出库单 */
        private Long lossDocId;
        /** 机器范围:盘亏配对的退库单(机器−→仓库+,再盘亏出库,仓库净不变) */
        private Long returnDocId;
        /** 机器范围:落锚点条数 */
        private int anchorCount;
        /** 机器范围:实盘仍为负、未落锚点的 SKU(账仍坏,提示下轮必须实盘) */
        private List<Long> skippedAnchorProducts = new ArrayList<>();
        /** 盘亏合计成本(负) / 盘盈合计成本(正) */
        private BigDecimal lossAmount;
        private BigDecimal gainAmount;
    }

    /** 损耗统计:按原因×月份(件数/成本额),供报表/驾驶舱 */
    @Data
    public static class LossStatRow {
        private String month;
        private String reason;
        private Long itemCount;
        private BigDecimal qty;
        private BigDecimal amount;
    }
}
