package top.aole.vend.modules.money.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.money.domain.entity.CashFlow;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface CashFlowMapper extends BaseMapper<CashFlow> {

    /** 账户净流水 = Σ(收) − Σ(支);余额 = 期初 + 本值(实时推算,附录D) */
    @Select("SELECT COALESCE(SUM(CASE WHEN direction='收' THEN amount ELSE -amount END),0) " +
            "FROM yc_vend_cash_flow WHERE account_id=#{accountId} AND is_deleted=0")
    BigDecimal sumNetByAccount(@Param("accountId") Long accountId);

    /** 批量版:account_id → 净流水 */
    @Select("<script>" +
            "SELECT account_id AS accountId, " +
            "COALESCE(SUM(CASE WHEN direction='收' THEN amount ELSE -amount END),0) AS net " +
            "FROM yc_vend_cash_flow WHERE is_deleted=0 AND account_id IN " +
            "<foreach collection='accountIds' item='aid' open='(' separator=',' close=')'>#{aid}</foreach> " +
            "GROUP BY account_id" +
            "</script>")
    List<Map<String, Object>> sumNetBatch(@Param("accountIds") Collection<Long> accountIds);

    /** 利润表取数口:pl_line × 入账月 聚合(收合计/支合计),期间闭区间可空 */
    @Select("<script>" +
            "SELECT pl_line AS plLine, book_period AS period, " +
            "COALESCE(SUM(CASE WHEN direction='收' THEN amount ELSE 0 END),0) AS inflow, " +
            "COALESCE(SUM(CASE WHEN direction='支' THEN amount ELSE 0 END),0) AS outflow " +
            "FROM yc_vend_cash_flow WHERE is_deleted=0 " +
            "<if test='fromPeriod != null'>AND book_period &gt;= #{fromPeriod} </if>" +
            "<if test='toPeriod != null'>AND book_period &lt;= #{toPeriod} </if>" +
            "GROUP BY pl_line, book_period ORDER BY book_period, pl_line" +
            "</script>")
    List<Map<String, Object>> plMonthlySummary(@Param("fromPeriod") String fromPeriod,
                                               @Param("toPeriod") String toPeriod);
}
