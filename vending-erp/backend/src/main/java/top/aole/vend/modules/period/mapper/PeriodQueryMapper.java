package top.aole.vend.modules.period.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 期间/上期调整专用只读查询(P0-2):
 * 报表"上期调整"行取数口——按 book_period 聚合 biz_period≠book_period 的补导记录。
 * 报表行本体由 M1-6(月报)/M4 接上,本 mapper 只负责出数。
 */
@Mapper
public interface PeriodQueryMapper {

    /**
     * 销售侧上期调整明细:入账月=#{bookPeriod} 且业务月≠入账月的 sale_record,
     * 按 业务月+导入批次 聚合(行数/数量/实收金额),供"上期调整"行下钻。
     */
    @Select("SELECT s.biz_period AS bizPeriod, s.import_batch_id AS importBatchId, " +
            "       COUNT(*) AS rowCount, COALESCE(SUM(s.qty),0) AS qty, " +
            "       COALESCE(SUM(s.amount_received),0) AS amount " +
            "FROM yc_vend_sale_record s " +
            "WHERE s.book_period=#{bookPeriod} AND s.biz_period <> s.book_period AND s.is_deleted=0 " +
            "GROUP BY s.biz_period, s.import_batch_id " +
            "ORDER BY s.biz_period, s.import_batch_id")
    List<Map<String, Object>> salePriorAdjust(@Param("bookPeriod") String bookPeriod);

    /**
     * 单据侧上期调整:入账月=#{bookPeriod} 且业务月≠入账月的已过账单据
     * (锁账后补录/红冲/成本调整入当月),按 业务月+单据类型 聚合。
     */
    @Select("SELECT DATE_FORMAT(d.biz_date,'%Y-%m') AS bizPeriod, d.doc_type AS docType, " +
            "       COUNT(*) AS docCount, COALESCE(SUM(d.total_amount),0) AS amount " +
            "FROM yc_vend_doc_head d " +
            "WHERE d.book_period=#{bookPeriod} AND DATE_FORMAT(d.biz_date,'%Y-%m') <> d.book_period " +
            "AND d.doc_status IN ('已确认','待结算','已结算','已完成','已红冲') AND d.is_deleted=0 " +
            "GROUP BY DATE_FORMAT(d.biz_date,'%Y-%m'), d.doc_type " +
            "ORDER BY bizPeriod")
    List<Map<String, Object>> docPriorAdjust(@Param("bookPeriod") String bookPeriod);
}
