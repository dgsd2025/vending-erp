package top.aole.vend.modules.pdca.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * PDCA C 指标取数(全部复用既有单一真相源表,本 mapper 只做聚合不另立台账):
 * 盘点损耗 → stocktake_item(口径与 StocktakeQueryMapper.lossStats 完全一致);
 * 带回率 → prekit_ticket(核销时已算好 takeback_rate,这里只取月均);
 * 实收差异/进价 → doc_head/doc_item(单据即台账,与 PurchasePriceMapper 同口径);
 * 动销/调价效果 → sale_record(补货口径 order_type IN 正常,兑换,与 DemandQueryMapper 同尺);
 * 售罄货道 → slot(current_qty 为推算值,与库存页同口径)。
 */
@Mapper
public interface PdcaQueryMapper {

    /** 采购入库落账口径(与 PurchasePriceMapper.CONFIRMED_STATUS 同尺) */
    String CONFIRMED_STATUS = "'已确认','待结算','已结算','已完成'";

    /** 某月某原因损耗成本额(已完成盘点的盘亏行;offline_exempt 剔除,与 lossStats 同口径) */
    @Select("<script>" +
            "SELECT COALESCE(SUM(-COALESCE(i.diff_amount,0)),0) " +
            "FROM yc_vend_stocktake_item i " +
            "JOIN yc_vend_stocktake st ON st.id=i.stocktake_id AND st.is_deleted=0 " +
            "WHERE i.is_deleted=0 AND st.st_status='已完成' AND i.diff_qty &lt; 0 " +
            "AND COALESCE(i.offline_exempt,0)=0 " +
            "AND DATE_FORMAT(st.snapshot_time,'%Y-%m')=#{period} " +
            "<if test='reason != null'>AND COALESCE(i.diff_reason,'原因不明')=#{reason} </if>" +
            "</script>")
    BigDecimal monthLossAmount(@Param("period") String period, @Param("reason") String reason);

    /** 某月已完成盘点单数(=0 时损耗指标不可判,防"没盘=0 损耗"误通过) */
    @Select("SELECT COUNT(*) FROM yc_vend_stocktake " +
            "WHERE is_deleted=0 AND st_status='已完成' AND DATE_FORMAT(snapshot_time,'%Y-%m')=#{period}")
    long monthStocktakeCount(@Param("period") String period);

    /** 某月账实符合率分子分母:已完成盘点的 总行数/差异行数 */
    @Select("SELECT COUNT(*) AS totalRows, " +
            "  SUM(CASE WHEN i.diff_qty != 0 THEN 1 ELSE 0 END) AS diffRows " +
            "FROM yc_vend_stocktake_item i " +
            "JOIN yc_vend_stocktake st ON st.id=i.stocktake_id AND st.is_deleted=0 " +
            "WHERE i.is_deleted=0 AND st.st_status='已完成' " +
            "AND DATE_FORMAT(st.snapshot_time,'%Y-%m')=#{period}")
    Map<String, Object> monthMatchRate(@Param("period") String period);

    /** 当前售罄货道:已绑商品的正常货道 总数/售罄数(current_qty 为推算值,与库存页同口径) */
    @Select("SELECT COUNT(*) AS totalSlots, " +
            "  SUM(CASE WHEN COALESCE(current_qty,0) <= 0 THEN 1 ELSE 0 END) AS outSlots " +
            "FROM yc_vend_slot WHERE is_deleted=0 AND product_id IS NOT NULL AND slot_status='正常'")
    Map<String, Object> stockoutSlots();

    /** 某月配货单平均带回率(已核销/有差异的单,verify_at 落在该月) */
    @Select("SELECT AVG(takeback_rate) FROM yc_vend_prekit_ticket " +
            "WHERE is_deleted=0 AND ticket_status IN ('已核销','有差异') " +
            "AND takeback_rate IS NOT NULL AND DATE_FORMAT(verify_at,'%Y-%m')=#{period}")
    BigDecimal monthTakebackAvg(@Param("period") String period);

    /** 某月采购入库单 总数/实收差异单数(expect_qty≠qty 任一行即差异单) */
    @Select("SELECT COUNT(*) AS totalReceipts, " +
            "  SUM(CASE WHEN diffItems > 0 THEN 1 ELSE 0 END) AS diffReceipts FROM (" +
            "  SELECT h.id, SUM(CASE WHEN i.expect_qty IS NOT NULL AND i.qty != i.expect_qty THEN 1 ELSE 0 END) AS diffItems " +
            "  FROM yc_vend_doc_head h JOIN yc_vend_doc_item i ON i.doc_id=h.id AND i.is_deleted=0 " +
            "  WHERE h.is_deleted=0 AND h.doc_type='采购入库' AND h.doc_status IN (" + CONFIRMED_STATUS + ") " +
            "  AND DATE_FORMAT(h.biz_date,'%Y-%m')=#{period} GROUP BY h.id) t")
    Map<String, Object> monthReceiptDiff(@Param("period") String period);

    /** 近 N 月落账采购明细(商品×单价×日期,Java 侧算最近价环比;小生意量级全取无碍) */
    @Select("SELECT i.product_id AS productId, i.unit_price AS unitPrice, h.biz_date AS bizDate, i.id AS itemId " +
            "FROM yc_vend_doc_item i JOIN yc_vend_doc_head h ON h.id=i.doc_id AND h.is_deleted=0 " +
            "WHERE i.is_deleted=0 AND h.doc_type='采购入库' AND h.doc_status IN (" + CONFIRMED_STATUS + ") " +
            "AND i.unit_price > 0 AND h.biz_date >= #{sinceDate} " +
            "ORDER BY i.product_id, h.biz_date DESC, i.id DESC")
    List<Map<String, Object>> purchaseItemsSince(@Param("sinceDate") java.time.LocalDate sinceDate);

    /** 某月动销 SKU 数(补货口径:正常+兑换,已归集 product_id) */
    @Select("SELECT COUNT(DISTINCT product_id) FROM yc_vend_sale_record " +
            "WHERE is_deleted=0 AND order_type IN ('正常','兑换') AND product_id IS NOT NULL " +
            "AND biz_period=#{period}")
    long monthSoldSkuCount(@Param("period") String period);

    /** 在售商品数(动销率分母) */
    @Select("SELECT COUNT(*) FROM yc_vend_product WHERE is_deleted=0 AND product_status='在售'")
    long onSaleProductCount();

    /** 某月改价次数(price_log,喂定价 PDCA) */
    @Select("SELECT COUNT(*) FROM yc_vend_price_log " +
            "WHERE is_deleted=0 AND DATE_FORMAT(effect_date,'%Y-%m')=#{period}")
    long monthPriceChangeCount(@Param("period") String period);

    /** 某商品最近一次改价(定价 A/B 自对照的锚点) */
    @Select("SELECT id, product_id AS productId, old_price AS oldPrice, new_price AS newPrice, effect_date AS effectDate " +
            "FROM yc_vend_price_log WHERE is_deleted=0 AND product_id=#{productId} " +
            "ORDER BY effect_date DESC, id DESC LIMIT 1")
    Map<String, Object> lastPriceChange(@Param("productId") Long productId);

    /** 某商品某时间窗销量合计(补货口径,调价前后 14 天对比用) */
    @Select("SELECT COALESCE(SUM(qty),0) FROM yc_vend_sale_record " +
            "WHERE is_deleted=0 AND order_type IN ('正常','兑换') AND product_id=#{productId} " +
            "AND biz_time >= #{from} AND biz_time < #{to}")
    BigDecimal salesQtyBetween(@Param("productId") Long productId,
                               @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
