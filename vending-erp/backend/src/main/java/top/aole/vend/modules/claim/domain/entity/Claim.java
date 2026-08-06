package top.aole.vend.modules.claim.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 索赔单(yc_vend_claim,§9.3 场景4 吞货索赔 · 穿行场景6 修正 P0-6)。
 *
 * 生命周期:申请中 →(赔付凭证必传)已到账(写 cash_flow 其他收入-赔付 + 回填 cash_flow_id)
 *          / 放弃(备注必填)。
 * 口径(§13.1 效力最高):
 * - 申请中金额 = 资产快照「索赔应收」(净流动资产公式第 4 项,M3-6 取数口 ClaimService#receivable);
 * - 净损耗 = 损耗 − 已获赔(损耗报表口,ClaimService#netShrinkage)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_claim")
public class Claim extends BaseEntity {

    public static final String STATUS_PENDING = "申请中";
    public static final String STATUS_RECEIVED = "已到账";
    public static final String STATUS_ABANDONED = "放弃";

    public static final String TARGET_FACTORY = "厂家";
    public static final String TARGET_PLATFORM = "平台";
    public static final Set<String> VALID_TARGETS = new LinkedHashSet<>(Arrays.asList(
            TARGET_FACTORY, TARGET_PLATFORM));

    /** 来源:盘亏归因(吞货/被盗)/其他 */
    public static final String SOURCE_STOCKTAKE = "盘亏";
    public static final String SOURCE_OTHER = "其他";

    private String claimNo;

    private String sourceType;

    /** 来源盘点单 ID(盘亏发起时) */
    private Long sourceId;

    /** 索赔对象:厂家/平台 */
    private String claimTarget;

    /** 索赔金额(申请中 = 计入索赔应收;默认=盘亏成本额,前端预填) */
    private BigDecimal amount;

    private String claimStatus;

    /** 实际到账金额(可与索赔金额不同,如厂家打折赔) */
    private BigDecimal receivedAmount;

    private LocalDateTime receivedTime;

    /** 到账生成的流水(其他收入-赔付),同事务回填 */
    private Long cashFlowId;

    /** 备注:放弃时必填(放弃原因);申请时可选说明 */
    private String remark;
}
