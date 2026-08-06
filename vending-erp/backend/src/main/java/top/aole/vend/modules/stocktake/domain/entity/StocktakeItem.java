package top.aole.vend.modules.stocktake.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 盘点明细(yc_vend_stocktake_item):账面快照/实盘/差异/原因。
 *
 * 创建盘点单时按范围全量快照账面数(actual 默认=book,diff=0)——"只录有差异的,
 * 未录=视同相符"由此天然成立;损耗报表按 diff_reason × 月份统计,反哺补货参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_stocktake_item")
public class StocktakeItem extends BaseEntity {

    /** 差异原因六枚举(差异行必选) */
    public static final String REASON_SWALLOW = "吞货掉货";
    public static final String REASON_EXPIRE = "过期报损";
    public static final String REASON_INPUT_ERROR = "录入错误";
    public static final String REASON_THEFT = "被盗";
    public static final String REASON_COUNT_ERROR = "盘点错误";
    public static final String REASON_UNKNOWN = "原因不明";

    public static final Set<String> VALID_REASONS = new LinkedHashSet<>(Arrays.asList(
            REASON_SWALLOW, REASON_EXPIRE, REASON_INPUT_ERROR,
            REASON_THEFT, REASON_COUNT_ERROR, REASON_UNKNOWN));

    /**
     * 账错类原因:差异根源是"账记错了"而非真实损耗(五步向导第 1 步的出口)。
     * 机器盘点对这类差异只做锚点校准,不生成财务性盘亏单(不算损耗)。
     */
    public static final Set<String> ACCOUNT_ERROR_REASONS = new LinkedHashSet<>(Arrays.asList(
            REASON_INPUT_ERROR, REASON_COUNT_ERROR));

    private Long stocktakeId;

    private Long productId;

    /** 货道号(机器盘点可到货道级,M2-4 先按 SKU 级) */
    private String slotNo;

    /** 账面快照数(仓库=Σledger;机器=锚点+增量推算值,可为负=缺锚点) */
    private BigDecimal bookQty;

    /** 实盘数(默认=book,未录视同相符) */
    private BigDecimal actualQty;

    /** 差异=实盘−账面 */
    private BigDecimal diffQty;

    /** 差异金额(diff × 移动加权成本,亏为负;无成本史 SKU 为 NULL) */
    private BigDecimal diffAmount;

    /** 差异原因(差异行必选) */
    private String diffReason;

    /** 吞货可索赔→关联索赔单(M3 开放) */
    private Long claimId;

    /** 线下销售差异豁免标记(P2-13 穿行场景5):豁免行不生成盘亏单 */
    private Boolean offlineExempt;
}
