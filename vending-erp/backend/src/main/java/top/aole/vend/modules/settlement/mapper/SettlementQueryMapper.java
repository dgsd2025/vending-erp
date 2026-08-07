package top.aole.vend.modules.settlement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算模块只读聚合 + 回填(M3-3)。
 *
 * 待结算口径(P0-3/§13.2-3,SQL 里写死,别处不许另抄一份):
 *   order_type IN ('正常','退款')(退款 amount_received 本来为负,SUM 自然相减)
 *   AND offline_flag=0(线下补录不入待结算;order_type 过滤已排除,双保险)
 *   AND is_deleted=0 —— 兑换/测试从 order_type 过滤天然排除。
 */
@Mapper
public interface SettlementQueryMapper {

    String SETTLE_SCOPE = "order_type IN ('正常','退款') AND offline_flag=0 AND is_deleted=0";

    // ============ PLATFORM:平台待结算虚账(实时算不落表) ============

    /** 待结算余额 = Σ(正常−退款,settlement_id IS NULL) */
    @Select("SELECT COALESCE(SUM(amount_received),0) FROM yc_vend_sale_record " +
            "WHERE settlement_id IS NULL AND " + SETTLE_SCOPE)
    BigDecimal pendingBalance();

    /** 待回填笔数 */
    @Select("SELECT COUNT(*) FROM yc_vend_sale_record WHERE settlement_id IS NULL AND " + SETTLE_SCOPE)
    long pendingCount();

    /** 最早一笔待回填业务时间(在途货款挂多久了) */
    @Select("SELECT MIN(biz_time) FROM yc_vend_sale_record WHERE settlement_id IS NULL AND " + SETTLE_SCOPE)
    LocalDateTime pendingOldest();

    /** 区间内待回填销售聚合(PLATFORM 确认时的系统额快照口径) */
    @Select("SELECT COALESCE(SUM(amount_received),0) FROM yc_vend_sale_record " +
            "WHERE settlement_id IS NULL AND " + SETTLE_SCOPE +
            " AND biz_time >= #{start} AND biz_time < #{endEx}")
    BigDecimal unsettledAmountIn(@Param("start") LocalDateTime start, @Param("endEx") LocalDateTime endEx);

    /** 回填:只碰 settlement_id IS NULL 的行(幂等——已归属别的结算单的行永不重写) */
    @Update("UPDATE yc_vend_sale_record SET settlement_id=#{sid} " +
            "WHERE settlement_id IS NULL AND " + SETTLE_SCOPE +
            " AND biz_time >= #{start} AND biz_time < #{endEx}")
    int backfill(@Param("sid") Long sid,
                 @Param("start") LocalDateTime start, @Param("endEx") LocalDateTime endEx);

    /** 退回填(M3-9 七律修复:已核销单红冲逆向)——只退归属本单的行,销售重新回「待结算」 */
    @Update("UPDATE yc_vend_sale_record SET settlement_id=NULL WHERE settlement_id=#{sid}")
    int unbackfill(@Param("sid") Long sid);

    // ============ DIRECT:商户账单核对(不看 settlement_id,全区间对总) ============

    /** 区间内系统销售额(直连核对口径:同三口径,但不筛回填状态——直连没有"在途"概念) */
    @Select("SELECT COALESCE(SUM(amount_received),0) FROM yc_vend_sale_record " +
            "WHERE " + SETTLE_SCOPE + " AND biz_time >= #{start} AND biz_time < #{endEx}")
    BigDecimal periodAmount(@Param("start") LocalDateTime start, @Param("endEx") LocalDateTime endEx);

    // ============ 兑换活动 ROI(Scenario04:兑换成本由补贴到账对冲) ============

    /** Σ兑换出货成本(移动加权快照;无采购史 NULL 行不计,另报缺数) */
    @Select("<script>SELECT COALESCE(SUM(cost_amount),0) FROM yc_vend_sale_record " +
            "WHERE order_type='兑换' AND is_deleted=0" +
            "<if test='start!=null'> AND biz_time &gt;= #{start}</if>" +
            "<if test='endEx!=null'> AND biz_time &lt; #{endEx}</if></script>")
    BigDecimal exchangeCost(@Param("start") LocalDateTime start, @Param("endEx") LocalDateTime endEx);

    /** 兑换出货件数 */
    @Select("<script>SELECT COALESCE(SUM(qty),0) FROM yc_vend_sale_record " +
            "WHERE order_type='兑换' AND is_deleted=0" +
            "<if test='start!=null'> AND biz_time &gt;= #{start}</if>" +
            "<if test='endEx!=null'> AND biz_time &lt; #{endEx}</if></script>")
    BigDecimal exchangeQty(@Param("start") LocalDateTime start, @Param("endEx") LocalDateTime endEx);

    /** 成本缺数行数(cost_amount IS NULL=无采购史,禁按 0 硬算,§13.2-6 → UI 标"N 行成本待补") */
    @Select("<script>SELECT COUNT(*) FROM yc_vend_sale_record " +
            "WHERE order_type='兑换' AND cost_amount IS NULL AND is_deleted=0" +
            "<if test='start!=null'> AND biz_time &gt;= #{start}</if>" +
            "<if test='endEx!=null'> AND biz_time &lt; #{endEx}</if></script>")
    long exchangeCostMissing(@Param("start") LocalDateTime start, @Param("endEx") LocalDateTime endEx);

    /** Σ厂家补贴确认额(deduction 非作废;抵扣确认单本身即"确认",按状态拆列) */
    @Select("SELECT COALESCE(SUM(amount),0) FROM yc_vend_deduction " +
            "WHERE ded_status=#{status} AND is_deleted=0")
    BigDecimal deductionTotalByStatus(@Param("status") String status);
}
