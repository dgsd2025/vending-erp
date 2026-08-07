package top.aole.vend.modules.purchase.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 采购价历史查询:全部从**已确认落账**的采购入库 doc_item 聚合(单据即台账,价格本不落静态表)。
 * 已确认落账 = doc_status IN (已确认/待结算/已结算/已完成);草稿/作废/已红冲不算数。
 */
@Mapper
public interface PurchasePriceMapper {

    String CONFIRMED_STATUS = "'已确认','待结算','已结算','已完成'";

    /**
     * 每商品×供应商 价格聚合:最近价/最近日期/最低价/采购次数。
     * supplierId 传 null = 该商品全部供应商各出一行(比价页)。
     */
    @Select("<script>" +
            "SELECT h.supplier_id AS supplierId, s.supplier_name AS supplierName, " +
            "  SUBSTRING_INDEX(GROUP_CONCAT(i.unit_price ORDER BY h.biz_date DESC, i.id DESC), ',', 1) AS lastPrice, " +
            "  MAX(h.biz_date) AS lastDate, MIN(i.unit_price) AS minPrice, COUNT(*) AS buyCount " +
            "FROM yc_vend_doc_item i " +
            "JOIN yc_vend_doc_head h ON h.id = i.doc_id AND h.is_deleted=0 " +
            "LEFT JOIN yc_vend_supplier s ON s.id = h.supplier_id " +
            "WHERE h.doc_type='采购入库' AND h.doc_status IN (" + CONFIRMED_STATUS + ") " +
            "AND i.is_deleted=0 AND i.product_id=#{productId} AND i.unit_price &gt; 0 " +
            "<if test='supplierId != null'>AND h.supplier_id=#{supplierId} </if>" +
            "GROUP BY h.supplier_id, s.supplier_name " +
            "ORDER BY lastDate DESC" +
            "</script>")
    List<Map<String, Object>> priceHistory(@Param("productId") Long productId,
                                           @Param("supplierId") Long supplierId);

    /**
     * 某商品最近一次落账进价(可选限定供应商、排除某单据自身)。
     * 进价变动写 price_log 时用 excludeDocId 排除刚确认的本单。
     */
    @Select("<script>" +
            "SELECT i.unit_price FROM yc_vend_doc_item i " +
            "JOIN yc_vend_doc_head h ON h.id = i.doc_id AND h.is_deleted=0 " +
            "WHERE h.doc_type='采购入库' AND h.doc_status IN (" + CONFIRMED_STATUS + ") " +
            "AND i.is_deleted=0 AND i.product_id=#{productId} AND i.unit_price &gt; 0 " +
            "<if test='supplierId != null'>AND h.supplier_id=#{supplierId} </if>" +
            "<if test='excludeDocId != null'>AND h.id != #{excludeDocId} </if>" +
            "ORDER BY h.biz_date DESC, i.id DESC LIMIT 1" +
            "</script>")
    BigDecimal lastPrice(@Param("productId") Long productId,
                         @Param("supplierId") Long supplierId,
                         @Param("excludeDocId") Long excludeDocId);

    /** 采购入库单列表(带供应商名/品项数/差异行数),小生意量级不分页 */
    @Select("SELECT h.id, h.doc_no AS docNo, h.biz_date AS bizDate, h.doc_status AS docStatus, " +
            "h.supplier_id AS supplierId, s.supplier_name AS supplierName, h.purchase_order_id AS purchaseOrderId, " +
            "h.total_qty AS totalQty, h.total_amount AS totalAmount, h.doc_source AS docSource, h.remark, " +
            "(SELECT COUNT(*) FROM yc_vend_doc_item i WHERE i.doc_id=h.id AND i.is_deleted=0) AS itemCount, " +
            "(SELECT COUNT(*) FROM yc_vend_doc_item i WHERE i.doc_id=h.id AND i.is_deleted=0 " +
            "   AND i.expect_qty IS NOT NULL AND i.qty != i.expect_qty) AS diffCount " +
            "FROM yc_vend_doc_head h " +
            "LEFT JOIN yc_vend_supplier s ON s.id = h.supplier_id " +
            "WHERE h.doc_type='采购入库' AND h.is_deleted=0 " +
            "ORDER BY h.id DESC LIMIT 200")
    List<Map<String, Object>> receiptList();

    /** 采购入库单明细(带商品名,详情页应收/实收两列) */
    @Select("SELECT i.id, i.product_id AS productId, pr.sku_code AS skuCode, pr.product_name AS productName, " +
            "i.qty, i.expect_qty AS expectQty, i.unit_price AS unitPrice, i.amount, i.po_item_id AS poItemId, i.remark " +
            "FROM yc_vend_doc_item i " +
            "LEFT JOIN yc_vend_product pr ON pr.id = i.product_id " +
            "WHERE i.doc_id=#{docId} AND i.is_deleted=0 ORDER BY i.id")
    List<Map<String, Object>> receiptItems(@Param("docId") Long docId);
}
