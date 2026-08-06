package top.aole.vend.modules.stocktake.money.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.AdjustRow;
import top.aole.vend.modules.stocktake.money.dto.CashMoneyDtos.SupplierPayableRow;

import java.util.List;

/**
 * M3-5 只读查询:
 * ① 供应商红冲出口跳转目标(最近一张正常结算单的来源单据)——应付余额本身不再在这里另抄
 *   口径(M3-9 P1-2:全系统唯一实现 = PayableService §7.3 公式,钱盘直接调它);
 * ② 资金调整单列表(doc_head × cash_adjust × account 三连)。
 */
@Mapper
public interface CashMoneyQueryMapper {

    /**
     * 各供应商最近一张正常结算单的来源单据 id(钱盘应付不符"红冲"出口的跳转目标)。
     * 只是单据指针,不涉金额口径——应付余额一律走 PayableService(M3-9 P1-2 单一真相源)。
     */
    @Select("SELECT supplier_id AS supplierId, " +
            "       SUBSTRING_INDEX(GROUP_CONCAT(source_doc_id ORDER BY id DESC), ',', 1) AS lastSourceDocId " +
            "FROM yc_vend_settle_bill " +
            "WHERE is_deleted = 0 AND bill_type = '应付' AND direction <> '红字' AND source_doc_id IS NOT NULL " +
            "GROUP BY supplier_id")
    List<SupplierPayableRow> lastSourceDocIds();

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
