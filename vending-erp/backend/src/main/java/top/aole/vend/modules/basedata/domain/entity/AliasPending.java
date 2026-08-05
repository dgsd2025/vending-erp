package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 别名待绑定队列(yc_vend_alias_pending)。
 * 导入遇到新 编号+条码 进队列,AI 给建议、人只点确认;确认后写入 sku_alias。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_alias_pending")
public class AliasPending extends BaseEntity {

    /** 未识别的后台商品编号 */
    private String aliasCode;

    /** 条码 */
    private String aliasBarcode;

    /** 后台商品名(展示用) */
    private String aliasName;

    /** 首次出现的导入批次 */
    private Long firstBatchId;

    /** 累计出现次数 */
    private Integer hitCount;

    /** AI 建议 SKU */
    private Long suggestProductId;

    /** AI 置信度 */
    private BigDecimal aiConfidence;

    /** AI 调用记录 */
    private Long llmCallId;

    /** 状态:待绑定/已绑定/已建新品/忽略 */
    private String pendingStatus;
}
