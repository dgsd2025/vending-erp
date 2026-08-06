package top.aole.vend.modules.finreport.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.aole.vend.modules.settlement.mapper.SettlementQueryMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * M3-6 只读取数口(自己写只读 SQL,不依赖并行票 M3-3 的结算类,票面边界):
 *
 * - 平台待结算(附录D/§13.1 第②项):PLATFORM 模式下 Σ(sale_record 正常+退款(负)且
 *   settlement_id IS NULL 的实收金额);兑换/测试/线下补录不参与(结算口径 §13.2-3)。
 * - 已核销平台结算单区间(P0-2 lock_diff_note):只读 yc_vend_settlement 行 +
 *   条件 UPDATE 只回写 lock_diff_note 一列(同 M3-4 回填 stocktake_item.claim_id 的
 *   跨模块纪律:不碰对方服务,只动自己负责的通道列)。
 */
@Mapper
public interface FinReportQueryMapper {

    // ============================== 平台待结算 ==============================

    /** 平台待结算余额 = Σ实收(正常+退款负,未回填结算单;M3-9 P1-3:口径复用 SETTLE_SCOPE 唯一常量) */
    @Select("SELECT COALESCE(SUM(amount_received),0) FROM yc_vend_sale_record " +
            "WHERE settlement_id IS NULL AND " + SettlementQueryMapper.SETTLE_SCOPE)
    BigDecimal platformPending();

    /** 待结算下钻:业务月 → 笔数/金额(p10 卡片"8 月在途货款"式来源列表) */
    @Select("SELECT biz_period AS period, COUNT(*) AS cnt, COALESCE(SUM(amount_received),0) AS amount " +
            "FROM yc_vend_sale_record WHERE settlement_id IS NULL AND " + SettlementQueryMapper.SETTLE_SCOPE +
            " GROUP BY biz_period ORDER BY biz_period")
    List<Map<String, Object>> platformPendingByPeriod();

    // ============================== 利润表取数 ==============================

    /** 可选月份 = 销售入账月 ∪ 流水入账月(升序) */
    @Select("SELECT DISTINCT p FROM (" +
            "SELECT book_period AS p FROM yc_vend_sale_record WHERE book_period IS NOT NULL " +
            "UNION SELECT book_period FROM yc_vend_cash_flow WHERE is_deleted=0 AND book_period IS NOT NULL" +
            ") t ORDER BY p")
    List<String> bookPeriods();

    /** 某入账月全部销售行(利润表口径原料;成本用 CostEngine replay 按 saleId 结转,不依赖回写) */
    @Select("SELECT id, amount_received AS amountReceived, order_type AS orderType, " +
            "product_id AS productId, biz_period AS bizPeriod, book_period AS bookPeriod " +
            "FROM yc_vend_sale_record WHERE book_period=#{period} AND is_deleted=0")
    List<Map<String, Object>> salesByBookPeriod(@Param("period") String period);

    // ============================== lock_diff_note 通道 ==============================

    /** 全部已核销平台结算单(区间+现有提示) */
    @Select("SELECT id, stmt_no AS stmtNo, period_start AS periodStart, period_end AS periodEnd, " +
            "stl_status AS stlStatus, lock_diff_note AS lockDiffNote " +
            "FROM yc_vend_settlement WHERE is_deleted=0 AND stl_status='已核销' ORDER BY period_start")
    List<Map<String, Object>> verifiedSettlements();

    /** 锁后补导命中区间统计:biz≠book(补导特征)且未含在任何结算单的正常/退款销售 */
    @Select("SELECT COUNT(*) AS cnt, COALESCE(SUM(amount_received),0) AS amount " +
            "FROM yc_vend_sale_record WHERE settlement_id IS NULL AND " + SettlementQueryMapper.SETTLE_SCOPE +
            " AND biz_period <> book_period AND DATE(biz_time) BETWEEN #{start} AND #{end}")
    Map<String, Object> backfillHitInRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * 厂家补贴按月聚合(M3-9 P1-4:利润表"其他收入-补贴"行取数):
     * 口径 = 抵扣确认单[已抵扣]金额,按其被带入的结算单入账月(settle_bill.book_period)归月——
     * 补贴不落现金流水(冲应付),直接聚合 deduction;现金到账补贴(EXCHANGE_SUBSIDY 流水)另行相加。
     */
    @Select("SELECT COALESCE(SUM(d.amount),0) FROM yc_vend_deduction d " +
            "JOIN yc_vend_settle_bill b ON b.id = d.used_settle_bill_id " +
            "WHERE d.ded_status='已抵扣' AND d.is_deleted=0 AND b.is_deleted=0 AND b.book_period=#{period}")
    BigDecimal subsidyUsedByPeriod(@Param("period") String period);

    /** 只写 lock_diff_note 一列,状态永不动(P0-2:只提示不改状态) */
    @Update("UPDATE yc_vend_settlement SET lock_diff_note=#{note} WHERE id=#{id} AND is_deleted=0")
    int updateSettlementLockDiffNote(@Param("id") Long id, @Param("note") String note);
}
