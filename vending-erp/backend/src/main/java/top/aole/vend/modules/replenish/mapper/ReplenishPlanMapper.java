package top.aole.vend.modules.replenish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.replenish.domain.entity.ReplenishPlan;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface ReplenishPlanMapper extends BaseMapper<ReplenishPlan> {

    /** 最近一次建议生成日(建议列表默认取这一天) */
    @Select("SELECT MAX(plan_date) FROM yc_vend_replenish_plan WHERE is_deleted=0")
    LocalDate latestPlanDate();

    /** 建议列表(带商品/机器名,p2 页秒开直接读表) */
    @Select("<script>" +
            "SELECT p.id, p.plan_date AS planDate, p.plan_type AS planType, p.machine_id AS machineId, " +
            "m.machine_name AS machineName, p.product_id AS productId, pr.sku_code AS skuCode, " +
            "pr.product_name AS productName, pr.unit, pr.box_spec AS boxSpec, pr.shelf_life_days AS shelfLifeDays, " +
            "p.current_qty AS currentQty, p.avg_daily AS avgDaily, p.sigma_daily AS sigmaDaily, " +
            "p.target_level_s AS targetLevelS, p.safety_stock AS safetyStock, p.suggest_qty AS suggestQty, " +
            "p.box_round_qty AS boxRoundQty, p.plan_status AS planStatus, p.ai_explain AS aiExplain, " +
            "p.llm_call_id AS llmCallId, p.formula_json AS formulaJson " +
            "FROM yc_vend_replenish_plan p " +
            "LEFT JOIN yc_vend_product pr ON pr.id = p.product_id " +
            "LEFT JOIN yc_vend_machine m ON m.id = p.machine_id " +
            "WHERE p.is_deleted=0 AND p.plan_date=#{planDate} AND p.plan_type=#{planType} " +
            "ORDER BY p.machine_id, p.suggest_qty DESC" +
            "</script>")
    List<Map<String, Object>> listByDateAndType(@Param("planDate") LocalDate planDate,
                                                @Param("planType") String planType);
}
