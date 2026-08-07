package top.aole.vend.modules.imports.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 期初导入向导出入参(M1-6)。三步:①商品档案+别名(一码多品清洗)②历史采购 ③历史销售,
 * 最后对平老账数字(采购/销售总额)才算「期初完成」。
 */
public final class InitialDtos {

    private InitialDtos() {
    }

    // ============================== 第①步:商品档案+别名 ==============================

    /** 一码多品冲突组(冲刺0实锤 SP009/010/011/012/046 等) */
    @Data
    public static class ConflictGroup {
        private String code;
        /** 同码下的多个商品名(按档案出现顺序) */
        private List<String> names = new ArrayList<>();
        /** 拆分后的新码预览(SP009A/SP009B…) */
        private List<String> splitCodes = new ArrayList<>();
    }

    @Data
    public static class Step1PreviewResp {
        private String token;
        private String fileName;
        private int productCount;
        private int aliasCount;
        private int machineCount;
        /** 一码多品冲突组:未给出处理方案(resolutions)整批不放行 */
        private List<ConflictGroup> conflicts = new ArrayList<>();
        /** 配比底稿有映射但档案缺失、需自动补建的编码(如 SP068) */
        private List<String> autoCreateCodes = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class ConflictResolution {
        @NotBlank
        private String code;
        /** split=拆分为新码(SP009A/SP009B,原码留 legacy_code);first=取首行(其余名并为别名) */
        @NotBlank
        private String mode;
    }

    @Data
    public static class Step1ConfirmReq {
        @NotBlank(message = "token 不能为空")
        private String token;
        private List<ConflictResolution> resolutions = new ArrayList<>();
    }

    @Data
    public static class Step1Resp {
        private Long batchId;
        private int productCreated;
        private int productSkipped;
        private int aliasCreated;
        private int machineCreated;
        private int splitProducts;
    }

    // ============================== 第②步:历史采购 ==============================

    @Data
    public static class Step2PreviewResp {
        private String token;
        private String fileName;
        private int rowCount;
        private BigDecimal totalQty;
        private BigDecimal totalAmt;
        private List<String> dates = new ArrayList<>();
        private List<String> supplierNames = new ArrayList<>();
        /** 找不到商品档案的行(需先完成第①步) */
        private List<String> missingProducts = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class Step2Resp {
        private Long batchId;
        private int docsCreated;
        private int itemCount;
        private BigDecimal totalAmt;
        private int supplierCreated;
        private int rowFail;
    }

    // ============================== 第③步:历史销售 ==============================

    @Data
    public static class Step3PreviewResp {
        private String token;
        private String fileName;
        private int rowCount;
        private BigDecimal totalAmt;
        private List<String> warnings = new ArrayList<>();
    }

    /** 第③步复用通道1处理,结果直接用 ImportDtos.CommitResp */

    // ============================== 状态 / 对平 ==============================

    @Data
    public static class StepState {
        private boolean done;
        private Long batchId;
        private String batchNo;
        private String batchStatus;
        private String doneAt;
        private int rowOk;
        private int rowFail;
    }

    @Data
    public static class StatusResp {
        private StepState step1 = new StepState();
        private StepState step2 = new StepState();
        private StepState step3 = new StepState();
        /** 三步全完成 */
        private boolean allStepsDone;
        private BigDecimal systemPurchaseTotal;
        private BigDecimal systemSaleTotal;
    }

    @Data
    public static class ValidateReq {
        /** 老账采购真值(冲刺0基准 27838.54) */
        private BigDecimal expectedPurchase;
        /** 老账销售真值(冲刺0基准 25113.50) */
        private BigDecimal expectedSale;
    }

    @Data
    public static class ValidateResp {
        private BigDecimal systemPurchase;
        private BigDecimal systemSale;
        private BigDecimal expectedPurchase;
        private BigDecimal expectedSale;
        private BigDecimal purchaseDiff;
        private BigDecimal saleDiff;
        private boolean purchasePass;
        private boolean salePass;
        /** 双过 = 期初完成(写 op_log 留痕) */
        private boolean pass;
    }
}
