package top.aole.vend.modules.task.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 任务日历专用只读校验查询(铁律#8:任务完成必有系统校验——导数据=真收到文件,不是打勾就算)。
 * 全部是 EXISTS/COUNT 口径,不落新实体;盘点/补货建议表由并行票生产数据,这里只读。
 */
@Mapper
public interface TaskQueryMapper {

    /** IMPORT_BATCH:当日存在成功导入批次(任意通道,批次状态=已导入) */
    @Select("SELECT COUNT(*) FROM yc_vend_import_batch " +
            "WHERE batch_status='已导入' AND DATE(create_time)=#{date} AND is_deleted=0")
    int importBatchOk(@Param("date") LocalDate date);

    /** REPLENISH ①:当日有已确认/已完成的出库上架单(仓库→机器转移单) */
    @Select("SELECT COUNT(*) FROM yc_vend_doc_head " +
            "WHERE doc_type='出库上架' AND doc_status IN ('已确认','已完成') " +
            "AND biz_date=#{date} AND is_deleted=0")
    int transferDocOk(@Param("date") LocalDate date);

    /** REPLENISH ②:当日补货建议总数(机器补货类) */
    @Select("SELECT COUNT(*) FROM yc_vend_replenish_plan " +
            "WHERE plan_date=#{date} AND plan_type='机器补货' AND is_deleted=0")
    int replenishPlanTotal(@Param("date") LocalDate date);

    /** REPLENISH ②:当日仍未处理的补货建议数(状态还停在"建议") */
    @Select("SELECT COUNT(*) FROM yc_vend_replenish_plan " +
            "WHERE plan_date=#{date} AND plan_type='机器补货' AND plan_status='建议' AND is_deleted=0")
    int replenishPlanOpen(@Param("date") LocalDate date);

    /** STOCKTAKE:当月存在已完成盘点单(盘点表并行开发中,先按 EXISTS 口径) */
    @Select("SELECT COUNT(*) FROM yc_vend_stocktake " +
            "WHERE st_status='已完成' AND DATE_FORMAT(create_time,'%Y-%m')=#{month} AND is_deleted=0")
    int stocktakeOk(@Param("month") String month);

    /** 员工详情:经手单据统计(op_log 按对象类型聚合,mockup p16"经手单据"卡) */
    @Select("SELECT target_type AS targetType, COUNT(*) AS cnt FROM yc_vend_op_log " +
            "WHERE user_name=#{userName} AND is_deleted=0 " +
            "GROUP BY target_type ORDER BY cnt DESC")
    List<Map<String, Object>> opLogStatsByUser(@Param("userName") String userName);
}
