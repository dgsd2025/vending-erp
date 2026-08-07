package top.aole.vend.modules.expense.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** M3-4 支出单/设备台账/线下收入复合单 DTO 集 */
public final class ExpenseDtos {

    private ExpenseDtos() {
    }

    // ============ 支出单 ============

    @Data
    public static class ExpenseCreateReq {
        /** 电费/维修/杂支/设备购置 */
        @NotBlank(message = "支出类别不能为空")
        private String category;
        @NotNull(message = "支出金额不能为空")
        private BigDecimal amount;
        @NotNull(message = "支出账户不能为空")
        private Long accountId;
        /** 支出日期(缺省=今天) */
        private LocalDate bizDate;
        /** 设备名称(类别=设备购置时必填) */
        private String equipName;
        private String remark;
    }

    @Data
    public static class ExpenseRow {
        private Long id;
        private String expNo;
        private String category;
        private BigDecimal amount;
        private Long accountId;
        private String accountName;
        private Boolean isEquipment;
        private Long equipmentId;
        private String equipName;
        private String expStatus;
        private LocalDate bizDate;
        private String bookPeriod;
        private String remark;
        private LocalDateTime createTime;
        /** 已传凭证数(确认门禁提示用) */
        private Long attachmentCount;
        /** 红冲指向的原支出单ID(非空=本行是负额红冲行) */
        private Long redFlushOf;
    }

    // ============ 设备台账 ============

    @Data
    public static class EquipmentRow {
        private Long id;
        private String equipName;
        private Long machineId;
        private BigDecimal buyPrice;
        private LocalDate buyDate;
        private BigDecimal residualValue;
        private String equipStatus;
        private Long expenseId;
        private LocalDateTime createTime;
    }

    @Data
    public static class EquipmentUpdateReq {
        @NotBlank(message = "设备名称不能为空")
        private String equipName;
        private Long machineId;
        /** 折余价值(展示用,可手工调) */
        private BigDecimal residualValue;
        /** 在用/报废/出售 */
        @NotBlank(message = "设备状态不能为空")
        private String equipStatus;
    }

    // ============ 线下收入复合单(P2-13) ============

    /** 逆向出口通用入参:备注强制留痕(作废/红冲/冲销) */
    @Data
    public static class NoteReq {
        private String note;
    }

    /** 一次录入 → 同事务三件套:sale_record(线下补录,不入待结算)+ cash_flow(其他收入-平台外)+ 豁免标记 */
    @Data
    public static class OfflineSaleReq {
        @NotNull(message = "机器不能为空")
        private Long machineId;
        @NotNull(message = "商品不能为空")
        private Long productId;
        @NotNull(message = "数量不能为空")
        private BigDecimal qty;
        /** 实收金额(如微信直转 13.2) */
        @NotNull(message = "实收金额不能为空")
        private BigDecimal amount;
        /** 收款账户(真实账户,老板微信/现金) */
        @NotNull(message = "收款账户不能为空")
        private Long accountId;
        /** 收款时间(缺省=当前) */
        private LocalDateTime bizTime;
        private String remark;
    }

    @Data
    public static class OfflineSaleResp {
        private Long saleRecordId;
        private String orderNo;
        private Long cashFlowId;
        /** 提示:机内库存差异由下次盘点勾「线下豁免」承接(offline_flag 已标) */
        private String exemptHint;
    }

    @Data
    public static class OfflineSaleRow {
        private Long saleRecordId;
        private String orderNo;
        private Long machineId;
        private Long productId;
        private BigDecimal qty;
        private BigDecimal amount;
        private LocalDateTime bizTime;
        /** 本行是冲销行(OFFLINE-RF- 负额) */
        private Boolean reversal;
        /** 本行(原复合单)已被冲销 */
        private Boolean reversed;
    }
}
