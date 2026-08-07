package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 改价留痕(yc_vend_price_log)。售价修改额外写本表,喂定价 PDCA。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_price_log")
public class PriceLog extends BaseEntity {

    /** 商品 SKU */
    private Long productId;

    /** 原售价 */
    private BigDecimal oldPrice;

    /** 新售价 */
    private BigDecimal newPrice;

    /** 来源:手工/导入侦测 */
    private String changeSource;

    /** 侦测来源批次 */
    private Long importBatchId;

    /** 生效日 */
    private LocalDate effectDate;

    /** 确认人 */
    private Long confirmBy;
}
