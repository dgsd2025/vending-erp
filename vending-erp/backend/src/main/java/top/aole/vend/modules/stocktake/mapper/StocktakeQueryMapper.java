package top.aole.vend.modules.stocktake.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.DocRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.ImportBatchRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.ItemRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.ListRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.LossStatRow;
import top.aole.vend.modules.stocktake.dto.StocktakeDtos.OfflineSaleRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 盘点模块只读查询(M2-4):列表汇总 / 明细带商品名 / 五步第 1 步查账 / 损耗统计。
 */
@Mapper
public interface StocktakeQueryMapper {

    /** 盘点单列表(带机器名 + 差异汇总),新→旧 */
    @Select("SELECT st.id, st.st_no, st.scope_type, st.machine_id, m.machine_name, " +
            "       st.snapshot_time, st.st_status, st.gain_doc_id, st.loss_doc_id, st.source_task, " +
            "       COALESCE(agg.diff_count,0) AS diffCount, agg.diff_qty AS diffQty, agg.diff_amount AS diffAmount " +
            "FROM yc_vend_stocktake st " +
            "LEFT JOIN yc_vend_machine m ON m.id = st.machine_id " +
            "LEFT JOIN (SELECT stocktake_id, " +
            "                  SUM(CASE WHEN diff_qty <> 0 THEN 1 ELSE 0 END) AS diff_count, " +
            "                  SUM(diff_qty) AS diff_qty, SUM(COALESCE(diff_amount,0)) AS diff_amount " +
            "           FROM yc_vend_stocktake_item WHERE is_deleted=0 GROUP BY stocktake_id) agg " +
            "       ON agg.stocktake_id = st.id " +
            "WHERE st.is_deleted=0 ORDER BY st.id DESC LIMIT #{limit}")
    List<ListRow> listRows(@Param("limit") int limit);

    /** 盘点明细(带商品名/编码),差异行在前、按商品序 */
    @Select("SELECT i.id, i.product_id, p.sku_code, p.product_name, i.slot_no, " +
            "       i.book_qty, i.actual_qty, i.diff_qty, i.diff_amount, i.diff_reason, i.offline_exempt " +
            "FROM yc_vend_stocktake_item i " +
            "LEFT JOIN yc_vend_product p ON p.id = i.product_id " +
            "WHERE i.stocktake_id=#{stocktakeId} AND i.is_deleted=0 " +
            "ORDER BY (i.diff_qty = 0), p.product_name, i.id")
    List<ItemRow> itemRows(@Param("stocktakeId") Long stocktakeId);

    /** 五步第 1 步①:近 N 天导入批次(漏导排查) */
    @Select("SELECT id, batch_no, file_type, batch_status, period_range, row_ok, create_time " +
            "FROM yc_vend_import_batch WHERE is_deleted=0 AND create_time >= #{since} " +
            "ORDER BY id DESC LIMIT 50")
    List<ImportBatchRow> recentImports(@Param("since") LocalDateTime since);

    /** 五步第 1 步②(仓库范围):近 N 天动仓库账的单据 */
    @Select("SELECT id, doc_no, doc_type, doc_status, doc_source, biz_date, total_qty " +
            "FROM yc_vend_doc_head WHERE is_deleted=0 AND create_time >= #{since} " +
            "AND doc_type IN ('采购入库','期初','盘盈入库','盘亏出库','报损','红冲','出库上架','退库') " +
            "ORDER BY id DESC LIMIT 50")
    List<DocRow> recentWarehouseDocs(@Param("since") LocalDateTime since);

    /** 五步第 1 步②(机器范围):近 N 天该机的转移单据 */
    @Select("SELECT id, doc_no, doc_type, doc_status, doc_source, biz_date, total_qty " +
            "FROM yc_vend_doc_head WHERE is_deleted=0 AND create_time >= #{since} " +
            "AND machine_id=#{machineId} AND doc_type IN ('出库上架','退库','红冲') " +
            "ORDER BY id DESC LIMIT 50")
    List<DocRow> recentMachineDocs(@Param("machineId") Long machineId,
                                   @Param("since") LocalDateTime since);

    /** 五步第 1 步③(机器范围):近 N 天线下补录销售(机器推算账不含线下出货,穿行场景5) */
    @Select("SELECT s.product_id, p.product_name, COUNT(*) AS cnt, COALESCE(SUM(s.qty),0) AS qty " +
            "FROM yc_vend_sale_record s LEFT JOIN yc_vend_product p ON p.id = s.product_id " +
            "WHERE s.is_deleted=0 AND s.machine_id=#{machineId} AND s.order_type='线下补录' " +
            "AND s.biz_time >= #{since} GROUP BY s.product_id, p.product_name")
    List<OfflineSaleRow> offlineSales(@Param("machineId") Long machineId,
                                      @Param("since") LocalDateTime since);

    /** 损耗统计:已完成盘点的盘亏行,按 原因×月份 汇总件数/成本额(正数表示损耗) */
    @Select("SELECT DATE_FORMAT(st.snapshot_time,'%Y-%m') AS month, " +
            "       COALESCE(i.diff_reason,'原因不明') AS reason, " +
            "       COUNT(*) AS itemCount, SUM(-i.diff_qty) AS qty, " +
            "       SUM(-COALESCE(i.diff_amount,0)) AS amount " +
            "FROM yc_vend_stocktake_item i " +
            "JOIN yc_vend_stocktake st ON st.id = i.stocktake_id AND st.is_deleted=0 " +
            "WHERE i.is_deleted=0 AND st.st_status='已完成' AND i.diff_qty < 0 " +
            "AND COALESCE(i.offline_exempt,0)=0 " +
            "AND st.snapshot_time >= #{since} " +
            "GROUP BY DATE_FORMAT(st.snapshot_time,'%Y-%m'), COALESCE(i.diff_reason,'原因不明') " +
            "ORDER BY month DESC, amount DESC")
    List<LossStatRow> lossStats(@Param("since") LocalDateTime since);
}
