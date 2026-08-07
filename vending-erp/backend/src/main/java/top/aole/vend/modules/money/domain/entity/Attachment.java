package top.aole.vend.modules.money.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

/**
 * 凭证附件(yc_vend_attachment,§9.4):凭证强制留痕通用件。
 *
 * 存储纪律(同导入中心 P0-B):归档文件名一律服务端生成(attNo+原扩展名),
 * **绝不拼接客户端原始文件名**(../../xx 路径穿越);原始名仅存 file_name 字段供展示。
 * 后续票规则:付款单无转账截图不能进"已付款",结算单无凭证不能进"已结算"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_attachment")
public class Attachment extends BaseEntity {

    /** 关联对象类型:doc_head/payment/settlement/claim/deduction/expense/stocktake */
    private String refType;

    /** 关联对象 ID */
    private Long refId;

    /** 凭证类型中文值(AttachmentType.label) */
    private String attType;

    /** 服务端存储路径 */
    private String filePath;

    /** 原文件名(仅展示,永不参与路径拼接) */
    private String fileName;

    /** AI 识别结果(金额/日期/对方,Qwen-VL;M4 接入点#2) */
    private String ocrJson;

    /** AI 调用记录 */
    private Long llmCallId;

    /** 上传人 */
    private Long uploadBy;
}
