package top.aole.vend.modules.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.stock.domain.entity.StockLedger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface StockLedgerMapper extends BaseMapper<StockLedger> {

    /** 仓库库存 = Σ流水(单 SKU) */
    @Select("SELECT COALESCE(SUM(change_qty),0) FROM yc_vend_stock_ledger " +
            "WHERE product_id=#{productId} AND location_type='仓库' AND is_deleted=0")
    BigDecimal sumWarehouse(@Param("productId") Long productId);

    /**
     * 盲审 P1-3:负库存拦截前对该 SKU 的商品行加行锁(SELECT ... FOR UPDATE),
     * 把"查余额 + 写流水"串行化——两张不同单据并发出库时,后到者等前者提交后
     * 才能读到最新余额,拦截不再被并发穿透。锁随本事务提交/回滚自动释放。
     */
    @Select("SELECT id FROM yc_vend_product WHERE id=#{productId} FOR UPDATE")
    Long lockProductRow(@Param("productId") Long productId);

    /**
     * 盲审 P1-3 配套:余额的**当前读**版本(FOR UPDATE)。
     * REPEATABLE READ 下普通 SELECT 是事务快照读——排队等到锁后读到的仍是旧余额,
     * 拦截照样被穿透;locking read 永远读最新已提交数据,配合 lockProductRow 才闭环。
     */
    @Select("SELECT COALESCE(SUM(change_qty),0) FROM yc_vend_stock_ledger " +
            "WHERE product_id=#{productId} AND location_type='仓库' AND is_deleted=0 FOR UPDATE")
    BigDecimal sumWarehouseCurrent(@Param("productId") Long productId);

    /** 仓库库存批量版:返回 product_id → Σ流水 */
    @Select("<script>" +
            "SELECT product_id AS productId, COALESCE(SUM(change_qty),0) AS qty " +
            "FROM yc_vend_stock_ledger " +
            "WHERE location_type='仓库' AND is_deleted=0 AND product_id IN " +
            "<foreach collection='productIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach> " +
            "GROUP BY product_id" +
            "</script>")
    List<Map<String, Object>> sumWarehouseBatch(@Param("productIds") Collection<Long> productIds);

    /** 机器侧转移增量:快照时刻之后按业务时间戳的机器账流水合计(出库上架+/退库-) */
    @Select("<script>" +
            "SELECT COALESCE(SUM(change_qty),0) FROM yc_vend_stock_ledger " +
            "WHERE location_type='机器' AND machine_id=#{machineId} AND product_id=#{productId} " +
            "AND is_deleted=0 " +
            "<if test='after != null'>AND biz_time &gt; #{after}</if>" +
            "</script>")
    BigDecimal sumMachineTransferAfter(@Param("machineId") Long machineId,
                                       @Param("productId") Long productId,
                                       @Param("after") LocalDateTime after);

    /** 某机器在机器账/快照/销售三处出现过的全部 SKU(全机汇总用) */
    @Select("SELECT DISTINCT product_id FROM (" +
            " SELECT product_id FROM yc_vend_stock_ledger WHERE location_type='机器' AND machine_id=#{machineId} AND is_deleted=0" +
            " UNION SELECT product_id FROM yc_vend_machine_stock_snapshot WHERE machine_id=#{machineId} AND is_deleted=0" +
            " UNION SELECT product_id FROM yc_vend_sale_record WHERE machine_id=#{machineId} AND product_id IS NOT NULL" +
            "   AND order_type IN ('正常','兑换') AND is_deleted=0" +
            ") t")
    List<Long> distinctMachineProducts(@Param("machineId") Long machineId);
}
