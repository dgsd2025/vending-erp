package top.aole.vend.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.purchase.domain.entity.PurchaseOrderItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface PurchaseOrderItemMapper extends BaseMapper<PurchaseOrderItem> {

    /** 在途状态集合:只有 已下单/部分到货 的订货单计入在途 */
    String ACTIVE_STATUS = "'已下单','部分到货'";

    /**
     * 单 SKU 在途 = Σ(qty_ordered − qty_received),仅统计 已下单/部分到货 订单。
     * 补货公式(M2)"当前库存(机内+在途)"唯一取数口。
     */
    @Select("SELECT COALESCE(SUM(i.qty_ordered - i.qty_received),0) " +
            "FROM yc_vend_purchase_order_item i " +
            "JOIN yc_vend_purchase_order p ON p.id = i.po_id AND p.is_deleted=0 " +
            "WHERE i.product_id=#{productId} AND i.is_deleted=0 " +
            "AND p.po_status IN (" + ACTIVE_STATUS + ")")
    BigDecimal sumInTransit(@Param("productId") Long productId);

    /** 全 SKU 在途汇总(>0 的才返回),带商品名 */
    @Select("SELECT i.product_id AS productId, pr.sku_code AS skuCode, pr.product_name AS productName, " +
            "SUM(i.qty_ordered - i.qty_received) AS inTransitQty " +
            "FROM yc_vend_purchase_order_item i " +
            "JOIN yc_vend_purchase_order p ON p.id = i.po_id AND p.is_deleted=0 " +
            "LEFT JOIN yc_vend_product pr ON pr.id = i.product_id " +
            "WHERE i.is_deleted=0 AND p.po_status IN (" + ACTIVE_STATUS + ") " +
            "GROUP BY i.product_id, pr.sku_code, pr.product_name " +
            "HAVING SUM(i.qty_ordered - i.qty_received) > 0")
    List<Map<String, Object>> sumInTransitAll();

    /** 单 SKU 在途明细行(按订货单展开),超期判断交给服务层 */
    @Select("SELECT p.id AS poId, p.po_no AS poNo, p.expect_date AS expectDate, p.po_status AS poStatus, " +
            "i.id AS poItemId, i.qty_ordered AS qtyOrdered, i.qty_received AS qtyReceived " +
            "FROM yc_vend_purchase_order_item i " +
            "JOIN yc_vend_purchase_order p ON p.id = i.po_id AND p.is_deleted=0 " +
            "WHERE i.product_id=#{productId} AND i.is_deleted=0 " +
            "AND p.po_status IN (" + ACTIVE_STATUS + ") " +
            "AND i.qty_ordered > i.qty_received " +
            "ORDER BY p.expect_date")
    List<Map<String, Object>> inTransitLines(@Param("productId") Long productId);

    /** 订货单明细带商品档案信息(名称/箱规),详情页与收货预填用 */
    @Select("SELECT i.id, i.po_id AS poId, i.product_id AS productId, i.qty_ordered AS qtyOrdered, " +
            "i.qty_received AS qtyReceived, i.unit_price AS unitPrice, i.amount, " +
            "pr.sku_code AS skuCode, pr.product_name AS productName, pr.box_spec AS boxSpec, pr.unit " +
            "FROM yc_vend_purchase_order_item i " +
            "LEFT JOIN yc_vend_product pr ON pr.id = i.product_id " +
            "WHERE i.po_id=#{poId} AND i.is_deleted=0 ORDER BY i.id")
    List<Map<String, Object>> itemsWithProduct(@Param("poId") Long poId);
}
