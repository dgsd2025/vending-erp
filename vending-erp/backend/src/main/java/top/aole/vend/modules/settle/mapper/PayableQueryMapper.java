package top.aole.vend.modules.settle.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 应付往来只读聚合(M3-2):余额各分项 + 对账单明细行。
 *
 * 口径(§7.3 问2,实时算不落表):
 *   应付余额 = 期初 + Σ已确认采购 − Σ退货 − Σ已用抵扣 − Σ已付款
 * 其中:
 *   Σ采购 = 采购入库单(已确认及之后所有状态,含已红冲——红冲由红冲单一行负项承接,不改写历史)
 *          − 指向采购入库的红冲单;
 *   Σ退货 = 带供应商的退库单(退供)同理净红冲;
 *   Σ已用抵扣 = deduction 已抵扣(红冲释放的回到待抵扣自动出列);
 *   Σ已付款 = pay_time 非空的付款单(钱真动了才算,差异挂起也已付)。
 */
@Mapper
public interface PayableQueryMapper {

    String CONFIRMED_STATES = "'已确认','待结算','已结算','已完成','已红冲'";

    /** Σ已确认采购(含已红冲原单,红冲另行负项) */
    @Select("SELECT COALESCE(SUM(total_amount),0) FROM yc_vend_doc_head " +
            "WHERE supplier_id=#{supplierId} AND doc_type='采购入库' " +
            "AND doc_status IN (" + CONFIRMED_STATES + ") AND is_deleted=0")
    BigDecimal purchaseTotal(@Param("supplierId") Long supplierId);

    /** Σ采购红冲(红冲单指向采购入库原单) */
    @Select("SELECT COALESCE(SUM(h.total_amount),0) FROM yc_vend_doc_head h " +
            "JOIN yc_vend_doc_head o ON o.id=h.red_flush_of " +
            "WHERE h.supplier_id=#{supplierId} AND h.doc_type='红冲' AND o.doc_type='采购入库' " +
            "AND h.doc_status IN ('已确认','已完成') AND h.is_deleted=0")
    BigDecimal purchaseRedTotal(@Param("supplierId") Long supplierId);

    /** Σ退货(带供应商的退库=退供) */
    @Select("SELECT COALESCE(SUM(total_amount),0) FROM yc_vend_doc_head " +
            "WHERE supplier_id=#{supplierId} AND doc_type='退库' " +
            "AND doc_status IN (" + CONFIRMED_STATES + ") AND is_deleted=0")
    BigDecimal returnTotal(@Param("supplierId") Long supplierId);

    /** Σ退货红冲 */
    @Select("SELECT COALESCE(SUM(h.total_amount),0) FROM yc_vend_doc_head h " +
            "JOIN yc_vend_doc_head o ON o.id=h.red_flush_of " +
            "WHERE h.supplier_id=#{supplierId} AND h.doc_type='红冲' AND o.doc_type='退库' " +
            "AND h.doc_status IN ('已确认','已完成') AND h.is_deleted=0")
    BigDecimal returnRedTotal(@Param("supplierId") Long supplierId);

    /** Σ已用抵扣 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM yc_vend_deduction " +
            "WHERE supplier_id=#{supplierId} AND ded_status='已抵扣' " +
            "AND used_settle_bill_id IS NOT NULL AND is_deleted=0")
    BigDecimal usedDeductionTotal(@Param("supplierId") Long supplierId);

    /** Σ已付款(pay_time 非空=钱真动了) */
    @Select("SELECT COALESCE(SUM(amount),0) FROM yc_vend_payment " +
            "WHERE supplier_id=#{supplierId} AND pay_time IS NOT NULL AND is_deleted=0")
    BigDecimal paymentTotal(@Param("supplierId") Long supplierId);

    // ============================== 对账单明细(期初+明细+期末) ==============================

    /** 拿货行:采购入库(正)+ 指向采购的红冲(负,发生在红冲单日,不改写历史) */
    @Select("SELECT h.biz_date AS bizDate, h.doc_no AS refNo, '拿货' AS lineType, " +
            "       h.total_amount AS amount, h.remark AS remark " +
            "FROM yc_vend_doc_head h WHERE h.supplier_id=#{supplierId} AND h.doc_type='采购入库' " +
            "AND h.doc_status IN (" + CONFIRMED_STATES + ") AND h.is_deleted=0 " +
            "UNION ALL " +
            "SELECT h.biz_date, h.doc_no, '拿货红冲', -h.total_amount, h.remark " +
            "FROM yc_vend_doc_head h JOIN yc_vend_doc_head o ON o.id=h.red_flush_of " +
            "WHERE h.supplier_id=#{supplierId} AND h.doc_type='红冲' AND o.doc_type='采购入库' " +
            "AND h.doc_status IN ('已确认','已完成') AND h.is_deleted=0")
    List<Map<String, Object>> purchaseLines(@Param("supplierId") Long supplierId);

    /** 退货行(负项;红冲回正) */
    @Select("SELECT h.biz_date AS bizDate, h.doc_no AS refNo, '退货' AS lineType, " +
            "       h.total_amount AS amount, h.remark AS remark " +
            "FROM yc_vend_doc_head h WHERE h.supplier_id=#{supplierId} AND h.doc_type='退库' " +
            "AND h.doc_status IN (" + CONFIRMED_STATES + ") AND h.is_deleted=0 " +
            "UNION ALL " +
            "SELECT h.biz_date, h.doc_no, '退货红冲', -h.total_amount, h.remark " +
            "FROM yc_vend_doc_head h JOIN yc_vend_doc_head o ON o.id=h.red_flush_of " +
            "WHERE h.supplier_id=#{supplierId} AND h.doc_type='红冲' AND o.doc_type='退库' " +
            "AND h.doc_status IN ('已确认','已完成') AND h.is_deleted=0")
    List<Map<String, Object>> returnLines(@Param("supplierId") Long supplierId);

    /** 抵扣行(已抵扣;日期=被带入结算单当日 update_time) */
    @Select("SELECT DATE(d.update_time) AS bizDate, d.ded_no AS refNo, '抵扣' AS lineType, " +
            "       d.amount AS amount, CONCAT(d.ded_source, COALESCE(CONCAT('·', d.period_desc), '')) AS remark, " +
            "       b.bill_no AS billNo " +
            "FROM yc_vend_deduction d LEFT JOIN yc_vend_settle_bill b ON b.id=d.used_settle_bill_id " +
            "WHERE d.supplier_id=#{supplierId} AND d.ded_status='已抵扣' AND d.is_deleted=0")
    List<Map<String, Object>> deductionLines(@Param("supplierId") Long supplierId);

    /** 付款行(钱真动了的) */
    @Select("SELECT DATE(p.pay_time) AS bizDate, p.pay_no AS refNo, '付款' AS lineType, " +
            "       p.amount AS amount, p.remark AS remark, a.account_name AS accountName " +
            "FROM yc_vend_payment p LEFT JOIN yc_vend_account a ON a.id=p.account_id " +
            "WHERE p.supplier_id=#{supplierId} AND p.pay_time IS NOT NULL AND p.is_deleted=0")
    List<Map<String, Object>> paymentLines(@Param("supplierId") Long supplierId);

    /** 结算单列表(带来源单号/供应商名/付款笔数,p8 详情"结算单链") */
    @Select("<script>" +
            "SELECT b.id, b.bill_no AS billNo, b.direction, b.supplier_id AS supplierId, " +
            "       s.supplier_name AS supplierName, b.source_doc_id AS sourceDocId, d.doc_no AS sourceDocNo, " +
            "       b.amount_due AS amountDue, b.deduction_amount AS deductionAmount, " +
            "       b.amount_actual AS amountActual, b.bill_status AS billStatus, b.diff_note AS diffNote, " +
            "       b.offset_into_bill_id AS offsetIntoBillId, b.due_date AS dueDate, b.create_time AS createTime " +
            "FROM yc_vend_settle_bill b " +
            "LEFT JOIN yc_vend_supplier s ON s.id=b.supplier_id " +
            "LEFT JOIN yc_vend_doc_head d ON d.id=b.source_doc_id " +
            "WHERE b.is_deleted=0 " +
            "<if test='supplierId != null'> AND b.supplier_id=#{supplierId} </if>" +
            "<if test='status != null and status != \"\"'> AND b.bill_status=#{status} </if>" +
            "ORDER BY b.id DESC" +
            "</script>")
    List<Map<String, Object>> billList(@Param("supplierId") Long supplierId, @Param("status") String status);

    /** 付款单列表(带账户名/结算单号,p8 详情"付款记录") */
    @Select("<script>" +
            "SELECT p.id, p.pay_no AS payNo, p.supplier_id AS supplierId, s.supplier_name AS supplierName, " +
            "       p.account_id AS accountId, a.account_name AS accountName, p.amount, " +
            "       p.deduction_amount AS deductionAmount, p.settle_bill_id AS settleBillId, b.bill_no AS billNo, " +
            "       p.pay_status AS payStatus, p.pay_time AS payTime, p.remark, p.create_time AS createTime " +
            "FROM yc_vend_payment p " +
            "LEFT JOIN yc_vend_supplier s ON s.id=p.supplier_id " +
            "LEFT JOIN yc_vend_account a ON a.id=p.account_id " +
            "LEFT JOIN yc_vend_settle_bill b ON b.id=p.settle_bill_id " +
            "WHERE p.is_deleted=0 " +
            "<if test='supplierId != null'> AND p.supplier_id=#{supplierId} </if>" +
            "ORDER BY p.id DESC" +
            "</script>")
    List<Map<String, Object>> paymentList(@Param("supplierId") Long supplierId);

    /** 某供应商最早的未付清正常结算单到期日(逾期黄灯取数) */
    @Select("SELECT MIN(due_date) FROM yc_vend_settle_bill " +
            "WHERE supplier_id=#{supplierId} AND direction='正常' " +
            "AND bill_status IN ('待确认','待付款','差异挂起') AND due_date IS NOT NULL AND is_deleted=0")
    java.time.LocalDate earliestUnpaidDueDate(@Param("supplierId") Long supplierId);
}
