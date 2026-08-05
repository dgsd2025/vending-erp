package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * SKU 别名映射(yc_vend_sku_alias)。
 * 冲刺 0 拍板:以 后台商品编号+条码 为主关联键,不绑名称;绑一次终身生效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_sku_alias")
public class SkuAlias extends BaseEntity {

    /** 后台商品编号(主关联键之一,可空存空串) */
    private String aliasCode;

    /** 后台条码(主关联键之二,可空存空串) */
    private String aliasBarcode;

    /** 后台销售商品名:仅留痕与展示,不参与唯一键 */
    private String aliasName;

    /** 归集到的采购 SKU(yc_vend_product.id) */
    private Long productId;

    /** 绑定来源:人工/AI建议采纳/商品列表导入 */
    private String bindSource;

    /** AI 归集置信度 */
    private BigDecimal aiConfidence;

    /** 关联 AI 调用记录 */
    private Long llmCallId;
}
