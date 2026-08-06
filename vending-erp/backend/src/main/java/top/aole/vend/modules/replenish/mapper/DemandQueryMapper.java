package top.aole.vend.modules.replenish.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 需求统计取数(M2-1):sale_record 日聚合。
 * 口径(§13.2-3 补货口径):order_type IN (正常,兑换),不含 测试/退款/线下补录;product_id 已归集。
 */
@Mapper
public interface DemandQueryMapper {

    /** 数据截至日 = 最后一笔出货的业务日期(数据新鲜度水印/统计窗口锚点) */
    @Select("SELECT DATE(MAX(biz_time)) FROM yc_vend_sale_record " +
            "WHERE order_type IN ('正常','兑换') AND is_deleted=0")
    LocalDate dataAsOf();

    /** 全局:每 SKU × 自然日销量(窗口内) */
    @Select("SELECT product_id AS productId, DATE(biz_time) AS d, SUM(qty) AS qty " +
            "FROM yc_vend_sale_record " +
            "WHERE order_type IN ('正常','兑换') AND is_deleted=0 AND product_id IS NOT NULL " +
            "AND biz_time >= #{start} AND biz_time < #{endExclusive} " +
            "GROUP BY product_id, DATE(biz_time)")
    List<Map<String, Object>> dailyGlobal(@Param("start") LocalDate start,
                                          @Param("endExclusive") LocalDate endExclusive);

    /** 分机器:每 机器 × SKU × 自然日销量(窗口内) */
    @Select("SELECT machine_id AS machineId, product_id AS productId, DATE(biz_time) AS d, SUM(qty) AS qty " +
            "FROM yc_vend_sale_record " +
            "WHERE order_type IN ('正常','兑换') AND is_deleted=0 " +
            "AND product_id IS NOT NULL AND machine_id IS NOT NULL " +
            "AND biz_time >= #{start} AND biz_time < #{endExclusive} " +
            "GROUP BY machine_id, product_id, DATE(biz_time)")
    List<Map<String, Object>> dailyByMachine(@Param("start") LocalDate start,
                                             @Param("endExclusive") LocalDate endExclusive);

    /** 每 SKU 首笔出货日期(样本不足判定:数据没满 28 天按实际跨度算日均) */
    @Select("SELECT product_id AS productId, DATE(MIN(biz_time)) AS firstDay " +
            "FROM yc_vend_sale_record " +
            "WHERE order_type IN ('正常','兑换') AND is_deleted=0 AND product_id IS NOT NULL " +
            "GROUP BY product_id")
    List<Map<String, Object>> firstSaleDay();
}
