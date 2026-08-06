package top.aole.vend.modules.stocktake.money.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.AdjustRow;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.SupplierPayableRow;

import java.util.List;

/**
 * M3-5 只读查询(不碰并行票 settle/claim/expense 的 Java 代码,只读它们的表):
 * ① 供应商应付余额:Σ正常结算实结 − Σ红字实结 − Σ已付款(与附录 §9 单据链同口径);
 * ② 资金调整单列表(doc_head × cash_adjust × account 三连)。
 */
@Mapper
public interface CashMoneyQueryMapper {

    /**
     * 供应商应付余额快照(钱盘应付核对的"系统数",供应商档案口径:期初+Σ往来):
     * 应付 = 期初应付 + Σ正常结算实结 − Σ红字实结 − Σ已付款;
     * 纳入「有过结算单 或 期初应付≠0」的供应商;source_doc_id 取最近一张正常结算单的
     * 来源单据(红冲出口的跳转目标)。
     */
    @Select("SELECT s.id AS supplierId, s.supplier_name AS supplierName, " +
            "       s.opening_payable + IFNULL(b.net, 0) - IFNULL(p.paid, 0) AS payable, b.lastSourceDocId " +
            "FROM yc_vend_supplier s " +
            "LEFT JOIN (SELECT supplier_id, " +
            "             SUM(CASE WHEN direction = '红字' THEN -amount_actual ELSE amount_actual END) AS net, " +
            "             SUBSTRING_INDEX(GROUP_CONCAT(CASE WHEN direction <> '红字' THEN source_doc_id END " +
            "                             ORDER BY id DESC), ',', 1) AS lastSourceDocId " +
            "      FROM yc_vend_settle_bill WHERE is_deleted = 0 AND bill_type = '应付' " +
            "      GROUP BY supplier_id) b ON b.supplier_id = s.id " +
            "LEFT JOIN (SELECT supplier_id, SUM(amount) AS paid FROM yc_vend_payment " +
            "           WHERE is_deleted = 0 AND pay_status IN ('已付款', '结算完成') " +
            "           GROUP BY supplier_id) p ON p.supplier_id = s.id " +
            "WHERE s.is_deleted = 0 AND (b.supplier_id IS NOT NULL OR s.opening_payable <> 0) " +
            "ORDER BY s.id")
    List<SupplierPayableRow> supplierPayables();

    /** 资金调整单列表(新→旧;带账户名/单据状态) */
    @Select("SELECT d.id AS docId, d.doc_no AS docNo, d.doc_status AS docStatus, d.biz_date AS bizDate, " +
            "       d.confirm_at AS confirmAt, d.remark, " +
            "       ca.account_id AS accountId, a.account_name AS accountName, " +
            "       ca.direction, ca.amount, ca.reason, ca.cash_check_id AS cashCheckId " +
            "FROM yc_vend_cash_adjust ca " +
            "JOIN yc_vend_doc_head d ON d.id = ca.doc_id " +
            "LEFT JOIN yc_vend_account a ON a.id = ca.account_id " +
            "WHERE ca.is_deleted = 0 ORDER BY d.id DESC LIMIT #{limit}")
    List<AdjustRow> adjustRows(int limit);
}
