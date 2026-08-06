package top.aole.vend.modules.replenish.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 补货建议(yc_vend_replenish_plan):(R,S) 引擎输出,机器补货 + 采购建议两类。
 * 规则出数字(formula_json 全量输入值快照),LLM 只出解释(ai_explain + llm_call_id 透明四件套)。
 * 幂等:同日重算覆盖 plan_status='建议' 的行,已忽略/已生成配货单的行保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_replenish_plan")
public class ReplenishPlan extends BaseEntity {

    /** 建议生成日 */
    private LocalDate planDate;

    /** 类型:机器补货(仓库→机器)/采购建议(供应商→仓库) */
    private String planType;

    /** 机器(采购建议为 NULL) */
    private Long machineId;

    /** 商品 SKU */
    private Long productId;

    /** 当前库存(机器侧=机内推算;采购侧=仓库+机内+在途) */
    private BigDecimal currentQty;

    /** 日均销量 d̄(近 28 天,口径=正常+兑换) */
    private BigDecimal avgDaily;

    /** 日销量标准差 σd */
    private BigDecimal sigmaDaily;

    /** 补到水位 S(机器侧=货道容量合计;采购侧=(R,S) 公式) */
    private BigDecimal targetLevelS;

    /** 安全库存 SS=Z×σd×√(R+L)(机器侧 NULL,不套公式) */
    private BigDecimal safetyStock;

    /** 建议补货量 Q */
    private BigDecimal suggestQty;

    /** 整箱向上取整后数量 */
    private BigDecimal boxRoundQty;

    /** 状态:建议/已采纳/已忽略/已生成配货单/已执行 */
    private String planStatus;

    /** 公式与全部输入值快照(🔬 这个数怎么算出来的) */
    private String formulaJson;

    /** LLM 人话解释(规则出数字,LLM 出解释) */
    private String aiExplain;

    /** AI 调用记录(透明四件套入口) */
    private Long llmCallId;
}
