package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 商品档案(yc_vend_product)。
 * 口径:售价仅参考价,毛利=实收-加权成本;停售≠删除,有流水的永不删,只改 product_status。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_product")
public class Product extends BaseEntity {

    /** SKU 编码(沿用 SP001 体系,租户内唯一) */
    private String skuCode;

    /** 老账原编码(一码多品清洗留痕) */
    private String legacyCode;

    /** 采购商品名(主名称) */
    private String productName;

    /** 条码 */
    private String barcode;

    /** 品类:饮料/泡面/面包/卤味/槟榔等 */
    private String category;

    /** 基本单位(瓶/袋/盒) */
    private String unit;

    /** 箱规:每整箱含基本单位数 */
    private BigDecimal boxSpec;

    /** 保质期天数 */
    private Integer shelfLifeDays;

    /** 参考成本:无采购史时兜底,禁止用 0 参与移动加权 */
    private BigDecimal refCost;

    /** 参考售价:真实收入以 sale_record 实收为准;改动写 price_log */
    private BigDecimal refPrice;

    /** 商品状态:在售/清仓中/停售 */
    private String productStatus;

    /** 进入清仓中的日期 */
    private LocalDate clearanceSince;

    /** 机内上限建议 */
    private BigDecimal minDisplayQty;

    private String remark;
}
