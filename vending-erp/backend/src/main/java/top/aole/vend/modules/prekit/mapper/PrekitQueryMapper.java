package top.aole.vend.modules.prekit.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 配货单/出库上架页专用查询(带名字直出,p5 页秒开)。
 */
@Mapper
public interface PrekitQueryMapper {

    /** 配货单列表(带机器名/行数/计划合计;status 空=全部) */
    @Select("<script>" +
            "SELECT t.id, t.ticket_no AS ticketNo, t.machine_id AS machineId, m.machine_name AS machineName, " +
            "t.plan_date AS planDate, t.ticket_status AS ticketStatus, t.verify_doc_id AS verifyDocId, " +
            "t.takeback_rate AS takebackRate, t.exec_at AS execAt, t.verify_at AS verifyAt, " +
            "t.create_time AS createTime, " +
            "(SELECT COUNT(*) FROM yc_vend_prekit_ticket_item i WHERE i.ticket_id=t.id AND i.is_deleted=0) AS itemCount, " +
            "(SELECT COALESCE(SUM(i.qty_planned),0) FROM yc_vend_prekit_ticket_item i WHERE i.ticket_id=t.id AND i.is_deleted=0) AS totalPlanned " +
            "FROM yc_vend_prekit_ticket t " +
            "LEFT JOIN yc_vend_machine m ON m.id = t.machine_id " +
            "WHERE t.is_deleted=0 " +
            "<if test='status != null and status != \"\"'>AND t.ticket_status=#{status} </if>" +
            "ORDER BY t.id DESC LIMIT 200" +
            "</script>")
    List<Map<String, Object>> listTickets(@Param("status") String status);

    /** 配货单明细行(带商品名/单位/条码) */
    @Select("SELECT i.id, i.ticket_id AS ticketId, i.product_id AS productId, i.plan_id AS planId, " +
            "p.sku_code AS skuCode, p.product_name AS productName, p.unit, p.barcode, " +
            "i.qty_planned AS qtyPlanned, i.qty_loaded AS qtyLoaded, i.qty_takeback AS qtyTakeback " +
            "FROM yc_vend_prekit_ticket_item i " +
            "LEFT JOIN yc_vend_product p ON p.id = i.product_id " +
            "WHERE i.ticket_id=#{ticketId} AND i.is_deleted=0 ORDER BY i.id")
    List<Map<String, Object>> listItems(@Param("ticketId") Long ticketId);

    /**
     * FEFO 提示级(M2-7):各 SKU 仓库最早入库日 = 最早一条仓库正向流水的业务日。
     * 完整批次/效期管理记债不做;此处只提示"先出旧货"。
     */
    @Select("<script>" +
            "SELECT product_id AS productId, MIN(DATE(biz_time)) AS earliestInDate " +
            "FROM yc_vend_stock_ledger " +
            "WHERE location_type='仓库' AND change_qty&gt;0 AND is_deleted=0 AND product_id IN " +
            "<foreach collection='productIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach> " +
            "GROUP BY product_id" +
            "</script>")
    List<Map<String, Object>> earliestInboundDates(@Param("productIds") Collection<Long> productIds);

    /**
     * 核销取数:某机器在 [from, to] 业务日窗口内、导入生成且已确认的出库上架单,按 SKU 汇总实上架量。
     */
    @Select("SELECT di.product_id AS productId, COALESCE(SUM(di.qty),0) AS loadedQty " +
            "FROM yc_vend_doc_head dh JOIN yc_vend_doc_item di ON di.doc_id = dh.id AND di.is_deleted=0 " +
            "WHERE dh.is_deleted=0 AND dh.doc_type='出库上架' AND dh.doc_source='导入' AND dh.doc_status='已确认' " +
            "AND dh.machine_id=#{machineId} AND dh.biz_date BETWEEN #{from} AND #{to} " +
            "GROUP BY di.product_id")
    List<Map<String, Object>> importedLoadedByMachine(@Param("machineId") Long machineId,
                                                      @Param("from") LocalDate from,
                                                      @Param("to") LocalDate to);

    /** 核销取数:窗口内首张匹配的导入转移单 ID(落 verify_doc_id 供追溯) */
    @Select("SELECT dh.id FROM yc_vend_doc_head dh " +
            "WHERE dh.is_deleted=0 AND dh.doc_type='出库上架' AND dh.doc_source='导入' AND dh.doc_status='已确认' " +
            "AND dh.machine_id=#{machineId} AND dh.biz_date BETWEEN #{from} AND #{to} " +
            "ORDER BY dh.id LIMIT 1")
    Long firstImportDocId(@Param("machineId") Long machineId,
                          @Param("from") LocalDate from,
                          @Param("to") LocalDate to);

    /** 转移单列表(出库上架/退库,带机器名/来源/状态/冲抵指向;p5 转移单卡) */
    @Select("SELECT dh.id, dh.doc_no AS docNo, dh.doc_type AS docType, dh.doc_status AS docStatus, " +
            "dh.doc_source AS docSource, dh.biz_date AS bizDate, dh.machine_id AS machineId, " +
            "m.machine_name AS machineName, dh.total_qty AS totalQty, dh.matched_doc_id AS matchedDocId, " +
            "dh.import_batch_id AS importBatchId, dh.confirm_at AS confirmAt, dh.remark, " +
            "(SELECT COUNT(*) FROM yc_vend_doc_item di WHERE di.doc_id=dh.id AND di.is_deleted=0) AS itemCount " +
            "FROM yc_vend_doc_head dh LEFT JOIN yc_vend_machine m ON m.id = dh.machine_id " +
            "WHERE dh.is_deleted=0 AND dh.doc_type IN ('出库上架','退库') " +
            "ORDER BY dh.id DESC LIMIT 200")
    List<Map<String, Object>> listTransfers();

    /**
     * 全期加权成本(元/件)= Σ入库金额 ÷ Σ入库数量(口径同 ImportQueryMapper.weightedCost,
     * 手工转移单录入带成本快照用;无采购史返回 NULL,禁 0 加权)。
     */
    @Select("SELECT SUM(amount)/SUM(change_qty) FROM yc_vend_stock_ledger " +
            "WHERE product_id=#{productId} AND location_type='仓库' AND change_qty>0 " +
            "AND unit_cost IS NOT NULL AND is_deleted=0")
    BigDecimal weightedCost(@Param("productId") Long productId);
}
