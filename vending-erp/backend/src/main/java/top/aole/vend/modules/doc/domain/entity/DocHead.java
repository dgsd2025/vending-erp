package top.aole.vend.modules.doc.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单据头(yc_vend_doc_head):一套状态机通吃 10 种单据类型。
 * 口径:单据即台账,不许删只红冲;出库上架=仓库→机器转移单。
 */
@Data
@TableName("yc_vend_doc_head")
public class DocHead {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    /** 单据号,如 RK-20260801-001 */
    private String docNo;
    private DocType docType;
    private DocStatus docStatus;
    /** 业务日期 */
    private LocalDate bizDate;
    /** 关联机器(出库上架的目标机/退库的来源机) */
    private Long machineId;
    private Long supplierId;
    /** 关联轻量订货单(P0-5,收货带"应收"列) */
    private Long purchaseOrderId;
    /** 数据来源:手工/导入/系统生成。转移单唯一生产者=导入(P0-4) */
    private String docSource;
    private Long importBatchId;
    /** 预挂单被导入单冲抵时指向导入生成的转移单 */
    private Long matchedDocId;
    /** 红冲指向的原单据 ID(P0-1,连锁逻辑 M1-7) */
    private Long redFlushOf;
    /** 负库存拦截豁免:仅导入生成的转移单与红冲/期初(P1-8),手工单不豁免 */
    private Boolean negStockExempt;
    private BigDecimal totalQty;
    private BigDecimal totalAmount;
    private LocalDate dueDate;
    private Long handlerUser;
    private Long confirmBy;
    private LocalDateTime confirmAt;
    private Long confirm2By;
    private LocalDateTime confirm2At;
    /** 入账月 YYYY-MM(P0-2 锁账×补导):确认时落账,业务月已锁 → 入当前月 */
    private String bookPeriod;
    /** 利润表行标记(M1-7 成本调整单已售部分='成本调整',M3 报表实装取数) */
    private String plLine;
    private String remark;

    private Long createUser;
    private Long updateUser;
}
