package top.aole.vend.modules.bi.mapper;

import org.apache.ibatis.annotations.Mapper;
import lombok.Data;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BI 只读取数(M4-1)。全部 SELECT,零写接口。
 * 销售/成本重放走 report.CostEngine(复用禁重造);这里只补 BI 特有的取数:
 * 带货道/支付方式的销售明细行、盘点损耗行、机器侧转移流水、快照锚点、采购归属、订货单实收差异、改价留痕。
 */
@Mapper
public interface BiQueryMapper {

    /** BI 销售明细(比 CostEngine 事件多 order_no/slot_no/pay_method,成本经 saleCost map 按 id 对上) */
    @Select("SELECT id, order_no AS orderNo, machine_id AS machineId, product_id AS productId, " +
            "       slot_no AS slotNo, qty, amount_received AS amountReceived, pay_method AS payMethod, " +
            "       order_type AS orderType, biz_time AS bizTime, biz_period AS bizPeriod " +
            "FROM yc_vend_sale_record WHERE is_deleted=0 ORDER BY biz_time, id")
    List<BiSaleRow> saleRows();

    /** 损耗行:已完成盘点的盘亏明细(线下豁免行不算损耗),带机器/货道/原因 */
    @Select("SELECT DATE_FORMAT(st.snapshot_time,'%Y-%m') AS month, st.machine_id AS machineId, " +
            "       i.slot_no AS slotNo, i.product_id AS productId, " +
            "       COALESCE(i.diff_reason,'原因不明') AS reason, " +
            "       -i.diff_qty AS qty, -COALESCE(i.diff_amount,0) AS amount " +
            "FROM yc_vend_stocktake_item i " +
            "JOIN yc_vend_stocktake st ON st.id = i.stocktake_id AND st.is_deleted=0 " +
            "WHERE i.is_deleted=0 AND st.st_status='已完成' AND i.diff_qty < 0 " +
            "AND COALESCE(i.offline_exempt,0)=0")
    List<BiLossRow> lossRows();

    /** 机器侧转移流水(出库上架 +/退库 −):缺货轨迹重建的增量之一 */
    @Select("SELECT machine_id AS machineId, product_id AS productId, change_qty AS changeQty, " +
            "       biz_time AS bizTime " +
            "FROM yc_vend_stock_ledger WHERE location_type='机器' AND is_deleted=0 " +
            "ORDER BY biz_time, id")
    List<BiMachineLedgerRow> machineLedgerRows();

    /** 机器库存快照锚点(同一时刻多货道行合并到机器×SKU) */
    @Select("SELECT machine_id AS machineId, product_id AS productId, snapshot_time AS snapshotTime, " +
            "       SUM(qty) AS qty " +
            "FROM yc_vend_machine_stock_snapshot WHERE is_deleted=0 " +
            "GROUP BY machine_id, product_id, snapshot_time ORDER BY snapshot_time")
    List<BiSnapshotRow> snapshotRows();

    /** 货道绑定(planogram):货道维行骨架 + 缺货估算范围(绑了货道的机器×SKU 才算缺货) */
    @Select("SELECT sl.machine_id AS machineId, sl.slot_no AS slotNo, sl.product_id AS productId, " +
            "       sl.capacity " +
            "FROM yc_vend_slot sl WHERE sl.is_deleted=0")
    List<BiSlotRow> slotRows();

    /** 供应商×SKU×月 采购额(确认过账的采购入库仓库正向流水) */
    @Select("SELECT d.supplier_id AS supplierId, l.product_id AS productId, " +
            "       DATE_FORMAT(l.biz_time,'%Y-%m') AS month, " +
            "       SUM(l.change_qty) AS qty, SUM(COALESCE(l.amount,0)) AS amount " +
            "FROM yc_vend_stock_ledger l " +
            "JOIN yc_vend_doc_head d ON d.id = l.doc_id " +
            "WHERE l.location_type='仓库' AND l.is_deleted=0 AND l.change_qty > 0 " +
            "AND d.doc_type='采购入库' AND d.supplier_id IS NOT NULL " +
            "GROUP BY d.supplier_id, l.product_id, DATE_FORMAT(l.biz_time,'%Y-%m')")
    List<BiSupplierPurchaseRow> supplierPurchaseRows();

    /** 供应商实收差异(已完成订货单:订购 vs 实收累计) */
    @Select("SELECT po.supplier_id AS supplierId, SUM(i.qty_ordered) AS ordered, " +
            "       SUM(i.qty_received) AS received " +
            "FROM yc_vend_purchase_order po " +
            "JOIN yc_vend_purchase_order_item i ON i.po_id = po.id AND i.is_deleted=0 " +
            "WHERE po.is_deleted=0 AND po.po_status='已完成' " +
            "GROUP BY po.supplier_id")
    List<BiPoDiffRow> poDiffRows();

    /** 改价留痕(有生效日的才有前后 14 天对比) */
    @Select("SELECT product_id AS productId, old_price AS oldPrice, new_price AS newPrice, " +
            "       effect_date AS effectDate, change_source AS changeSource " +
            "FROM yc_vend_price_log WHERE is_deleted=0 AND effect_date IS NOT NULL " +
            "ORDER BY effect_date DESC, id DESC")
    List<BiPriceLogRow> priceLogRows();

    /** 月上架量(出库上架确认过账,机器正向行):售罄率分母 */
    @Select("SELECT l.machine_id AS machineId, l.product_id AS productId, " +
            "       DATE_FORMAT(l.biz_time,'%Y-%m') AS month, SUM(l.change_qty) AS qty " +
            "FROM yc_vend_stock_ledger l " +
            "JOIN yc_vend_doc_head d ON d.id = l.doc_id " +
            "WHERE l.location_type='机器' AND l.change_qty > 0 AND l.is_deleted=0 " +
            "AND d.doc_type='出库上架' " +
            "GROUP BY l.machine_id, l.product_id, DATE_FORMAT(l.biz_time,'%Y-%m')")
    List<BiLoadRow> loadRows();

    // ============================== 行结构 ==============================

    @Data
    class BiSaleRow {
        private Long id;
        private String orderNo;
        private Long machineId;
        private Long productId;
        private String slotNo;
        private java.math.BigDecimal qty;
        private java.math.BigDecimal amountReceived;
        private String payMethod;
        private String orderType;
        private LocalDateTime bizTime;
        private String bizPeriod;
    }

    @Data
    class BiLossRow {
        private String month;
        private Long machineId;
        private String slotNo;
        private Long productId;
        private String reason;
        private java.math.BigDecimal qty;
        private java.math.BigDecimal amount;
    }

    @Data
    class BiMachineLedgerRow {
        private Long machineId;
        private Long productId;
        private java.math.BigDecimal changeQty;
        private LocalDateTime bizTime;
    }

    @Data
    class BiSnapshotRow {
        private Long machineId;
        private Long productId;
        private LocalDateTime snapshotTime;
        private java.math.BigDecimal qty;
    }

    @Data
    class BiSlotRow {
        private Long machineId;
        private String slotNo;
        private Long productId;
        private java.math.BigDecimal capacity;
    }

    @Data
    class BiSupplierPurchaseRow {
        private Long supplierId;
        private Long productId;
        private String month;
        private java.math.BigDecimal qty;
        private java.math.BigDecimal amount;
    }

    @Data
    class BiPoDiffRow {
        private Long supplierId;
        private java.math.BigDecimal ordered;
        private java.math.BigDecimal received;
    }

    @Data
    class BiPriceLogRow {
        private Long productId;
        private java.math.BigDecimal oldPrice;
        private java.math.BigDecimal newPrice;
        private java.time.LocalDate effectDate;
        private String changeSource;
    }

    @Data
    class BiLoadRow {
        private Long machineId;
        private Long productId;
        private String month;
        private java.math.BigDecimal qty;
    }
}
