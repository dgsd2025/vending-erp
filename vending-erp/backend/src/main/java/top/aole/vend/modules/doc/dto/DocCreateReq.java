package top.aole.vend.modules.doc.dto;

import lombok.Data;
import top.aole.vend.modules.doc.domain.enums.DocType;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * 建单/改草稿入参(单据头+明细)。
 */
@Data
public class DocCreateReq {

    @NotNull(message = "单据类型不能为空")
    private DocType docType;
    @NotNull(message = "业务日期不能为空")
    private LocalDate bizDate;
    /** 转移类单据(出库上架/退库)必填 */
    private Long machineId;
    private Long supplierId;
    private Long purchaseOrderId;
    /** 手工/导入/系统生成,默认手工 */
    private String docSource;
    private Long importBatchId;
    /** 红冲原单 ID(doc_type=红冲时必填,连锁逻辑 M1-7) */
    private Long redFlushOf;
    private LocalDate dueDate;
    private Long handlerUser;
    private String remark;

    @Valid
    @NotEmpty(message = "单据明细不能为空")
    private List<DocItemReq> items;
}
