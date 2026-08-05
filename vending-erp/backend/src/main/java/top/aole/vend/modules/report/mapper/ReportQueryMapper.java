package top.aole.vend.modules.report.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.aole.vend.modules.report.dto.ReportDtos.LedgerEvent;
import top.aole.vend.modules.report.dto.ReportDtos.SaleEvent;
import top.aole.vend.modules.report.dto.ReportDtos.StockLedgerRow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 报表模块只读查询(M1-6):成本引擎事件流 + 水印 + 单品流水。
 */
@Mapper
public interface ReportQueryMapper {

    /**
     * 成本引擎输入①:全部仓库账流水(带单据类型),按业务时间时序。
     * 同一时刻先入库后出库(id 升序兜底);qty=0 成本调整行也在其中(附录C)。
     */
    @Select("SELECT l.id, l.product_id, d.doc_type, l.change_qty, l.amount, l.unit_cost, l.biz_time " +
            "FROM yc_vend_stock_ledger l JOIN yc_vend_doc_head d ON d.id = l.doc_id " +
            "WHERE l.location_type='仓库' AND l.is_deleted=0 " +
            "ORDER BY l.biz_time, l.id")
    List<LedgerEvent> ledgerEvents();

    /** 成本引擎输入②:全部销售事件(含未绑定行 product_id NULL,单独出「未绑定」聚合行) */
    @Select("SELECT id, product_id, machine_id, qty, amount_received, order_type, biz_time, biz_period " +
            "FROM yc_vend_sale_record WHERE is_deleted=0 ORDER BY biz_time, id")
    List<SaleEvent> saleEvents();

    /** 数据截至水印:流水/销售/机器快照三处最大业务时间 */
    @Select("SELECT GREATEST(" +
            " COALESCE((SELECT MAX(biz_time) FROM yc_vend_stock_ledger WHERE is_deleted=0), '1970-01-01')," +
            " COALESCE((SELECT MAX(biz_time) FROM yc_vend_sale_record WHERE is_deleted=0), '1970-01-01')," +
            " COALESCE((SELECT MAX(snapshot_time) FROM yc_vend_machine_stock_snapshot WHERE is_deleted=0), '1970-01-01'))")
    LocalDateTime dataAsOf();

    /** 单品流水(仓库+机器两本账,时序倒排),点开看流水 */
    @Select("SELECT l.id, l.biz_time, d.doc_no, d.doc_type, l.location_type, m.machine_name, " +
            "       l.change_qty, l.balance_qty, l.unit_cost, l.amount " +
            "FROM yc_vend_stock_ledger l " +
            "JOIN yc_vend_doc_head d ON d.id = l.doc_id " +
            "LEFT JOIN yc_vend_machine m ON m.id = l.machine_id " +
            "WHERE l.product_id=#{productId} AND l.is_deleted=0 " +
            "ORDER BY l.biz_time DESC, l.id DESC LIMIT #{limit}")
    List<StockLedgerRow> productLedger(@Param("productId") Long productId, @Param("limit") int limit);

    /** 回写①:销售行成本快照 */
    @Update("UPDATE yc_vend_sale_record SET cost_amount=#{cost} WHERE id=#{id}")
    int updateSaleCost(@Param("id") Long id, @Param("cost") BigDecimal cost);

    /** 回写②:流水行(出库/转移)加权成本快照(附录C:出库按当前单位成本结转) */
    @Update("UPDATE yc_vend_stock_ledger SET unit_cost=#{unitCost}, amount=#{amount} WHERE id=#{id}")
    int updateLedgerCost(@Param("id") Long id,
                         @Param("unitCost") BigDecimal unitCost,
                         @Param("amount") BigDecimal amount);

    /** 期初对平:系统总采购额(期初/采购入库 已确认单据合计) */
    @Select("SELECT COALESCE(SUM(total_amount),0) FROM yc_vend_doc_head " +
            "WHERE doc_type IN ('期初','采购入库') " +
            "AND doc_status NOT IN ('已作废','已红冲','草稿','待确认') AND is_deleted=0")
    BigDecimal systemPurchaseTotal();

    /** 期初对平:系统总销售额(全部订单类型实收合计,退款为负) */
    @Select("SELECT COALESCE(SUM(amount_received),0) FROM yc_vend_sale_record " +
            "WHERE is_deleted=0 AND order_type <> '测试'")
    BigDecimal systemSaleTotal();

    /** 机器清单(库存页列头) */
    @Select("SELECT id AS machineId, machine_name AS machineName FROM yc_vend_machine " +
            "WHERE is_deleted=0 ORDER BY id")
    List<Map<String, Object>> machineCols();
}
