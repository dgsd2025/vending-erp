package top.aole.vend.modules.doc.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 单据中心只读聚合查询(M1-7:红冲影响清单/成本调整取数)。
 */
@Mapper
public interface DocQueryMapper {

    /** 单据明细行 + 商品名/编码(影响清单/成本调整选择行用) */
    @Select("SELECT i.id AS docItemId, i.product_id AS productId, p.sku_code AS skuCode, " +
            "       p.product_name AS productName, i.qty AS qty, i.unit_price AS unitPrice, " +
            "       i.amount AS amount, i.remark AS remark " +
            "FROM yc_vend_doc_item i LEFT JOIN yc_vend_product p ON p.id = i.product_id " +
            "WHERE i.doc_id=#{docId} AND i.is_deleted=0 ORDER BY i.id")
    List<Map<String, Object>> itemsWithProduct(@Param("docId") Long docId);

    /** 原单库存流水按 月份+账本 聚合金额/数量(红冲影响清单:涉及月份毛利/存货成本变动) */
    @Select("SELECT DATE_FORMAT(l.biz_time,'%Y-%m') AS period, l.location_type AS locationType, " +
            "       COALESCE(SUM(l.change_qty),0) AS qty, COALESCE(SUM(l.amount),0) AS amount " +
            "FROM yc_vend_stock_ledger l WHERE l.doc_id=#{docId} AND l.is_deleted=0 " +
            "GROUP BY DATE_FORMAT(l.biz_time,'%Y-%m'), l.location_type ORDER BY period")
    List<Map<String, Object>> ledgerByPeriod(@Param("docId") Long docId);

    /** 下游检查①:被哪些预挂单冲抵关联(matched_doc_id 指向本单) */
    @Select("SELECT id, doc_no AS docNo, doc_status AS docStatus FROM yc_vend_doc_head " +
            "WHERE matched_doc_id=#{docId} AND is_deleted=0")
    List<Map<String, Object>> matchedByPending(@Param("docId") Long docId);

    /**
     * 某商品自某业务时点起的已售数量(口径=正常+兑换,与补货日均同源,P0-3)。
     * 成本调整"未售/已售"切分用:已售=min(本行采购量, 该时点后销量),FIFO 近似,不追溯已售成本。
     */
    @Select("SELECT COALESCE(SUM(qty),0) FROM yc_vend_sale_record " +
            "WHERE product_id=#{productId} AND order_type IN ('正常','兑换') " +
            "AND biz_time >= #{since} AND is_deleted=0")
    java.math.BigDecimal soldSince(@Param("productId") Long productId,
                                   @Param("since") java.time.LocalDateTime since);

    /** 下游检查②:已存在的红冲/成本调整单(red_flush_of 指向本单,未作废) */
    @Select("SELECT id, doc_no AS docNo, doc_type AS docType, doc_status AS docStatus " +
            "FROM yc_vend_doc_head WHERE red_flush_of=#{docId} AND doc_status <> '已作废' AND is_deleted=0")
    List<Map<String, Object>> reverseDocsOf(@Param("docId") Long docId);
}
