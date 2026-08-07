package top.aole.vend.modules.imports.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 导入中心专用只读查询(跨表聚合,不落新实体)。
 */
@Mapper
public interface ImportQueryMapper {

    /**
     * 全期加权成本(元/件)= Σ入库金额 ÷ Σ入库数量(仓库账入方向、有成本的流水)。
     * 口径对齐冲刺0对平(成本单价=该SKU全期采购加权价);无采购史返回 NULL(§13 场景7,禁 0 加权)。
     * M1-6 移动加权成本引擎上线后由其替换。
     */
    @Select("SELECT SUM(amount)/SUM(change_qty) FROM yc_vend_stock_ledger " +
            "WHERE product_id=#{productId} AND location_type='仓库' AND change_qty>0 " +
            "AND unit_cost IS NOT NULL AND is_deleted=0")
    BigDecimal weightedCost(@Param("productId") Long productId);

    /** 已存在的销售去重键(order_no + order_type),防重预载 */
    @Select("SELECT order_no AS orderNo, order_type AS orderType FROM yc_vend_sale_record WHERE is_deleted=0")
    List<Map<String, Object>> existingSaleKeys();

    /**
     * 某业务月是否在锁账线内(P0-2:锁后补导 book_period=当月)。
     * M1-7 接上锁账线语义:锁账=锁定某 YYYY-MM **及之前所有月**,
     * 所以判定是"存在 period ≥ 该业务月 的锁记录"(锁账线=MAX(period)),而非精确匹配。
     */
    @Select("SELECT COUNT(*) FROM yc_vend_period_lock WHERE period >= #{period} AND is_deleted=0")
    int periodLocked(@Param("period") String period);

    /** 机器账里该时间戳是否已有流水(通道2防重:同机器+商品+补货时间戳) */
    @Select("SELECT COUNT(*) FROM yc_vend_stock_ledger " +
            "WHERE location_type='机器' AND machine_id=#{machineId} AND product_id=#{productId} " +
            "AND biz_time=#{bizTime} AND is_deleted=0")
    int machineLedgerExists(@Param("machineId") Long machineId,
                            @Param("productId") Long productId,
                            @Param("bizTime") java.time.LocalDateTime bizTime);

    /** 通道1改价侦测清单:批次内 已归集行 按商品聚合出最近成交价,与档案参考价不同的 */
    @Select("SELECT s.product_id AS productId, p.sku_code AS skuCode, p.product_name AS productName, " +
            "       p.ref_price AS refPrice, " +
            "       SUBSTRING_INDEX(GROUP_CONCAT(s.unit_price ORDER BY s.biz_time DESC), ',', 1) AS latestPrice, " +
            "       COUNT(*) AS rowCount " +
            "FROM yc_vend_sale_record s JOIN yc_vend_product p ON p.id = s.product_id " +
            "WHERE s.import_batch_id=#{batchId} AND s.is_deleted=0 AND s.product_id IS NOT NULL " +
            "AND s.unit_price IS NOT NULL " +
            "GROUP BY s.product_id, p.sku_code, p.product_name, p.ref_price")
    List<Map<String, Object>> salePriceGroups(@Param("batchId") Long batchId);

    /** 批次涉及商品当前仓库结存(负库存红灯:待补录采购) */
    @Select("SELECT l.product_id AS productId, p.sku_code AS skuCode, p.product_name AS productName, " +
            "       SUM(l.change_qty) AS balance " +
            "FROM yc_vend_stock_ledger l JOIN yc_vend_product p ON p.id = l.product_id " +
            "WHERE l.location_type='仓库' AND l.is_deleted=0 AND l.product_id IN (" +
            "  SELECT DISTINCT l2.product_id FROM yc_vend_stock_ledger l2 " +
            "  JOIN yc_vend_doc_head d ON d.id = l2.doc_id " +
            "  WHERE d.import_batch_id=#{batchId} AND l2.is_deleted=0) " +
            "GROUP BY l.product_id, p.sku_code, p.product_name HAVING SUM(l.change_qty) < 0")
    List<Map<String, Object>> negativeWarehouseOfBatch(@Param("batchId") Long batchId);
}
