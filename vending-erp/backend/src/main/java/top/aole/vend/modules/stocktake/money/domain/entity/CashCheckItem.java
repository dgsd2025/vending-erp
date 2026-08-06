package top.aole.vend.modules.stocktake.money.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * 钱盘核对明细(yc_vend_cash_check_item):一行 = 一个核对对象的 系统数 vs 实际数。
 *
 * 差异出口(审计 P1-7):
 * - 账户行:差异唯一出口 = 生成资金调整单(adjust_doc_id 回链,防重复生成);
 * - 平台行:差异走平台结算单核销(M3-4),本表只留档不开口;
 * - 应付行:不符出口 = 补录(跳付款/抵扣录入)或 红冲(跳对应单据),exit_action 留痕。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_cash_check_item")
public class CashCheckItem extends BaseEntity {

    public static final String TYPE_ACCOUNT = "账户";
    public static final String TYPE_PLATFORM = "平台";
    public static final String TYPE_PAYABLE = "应付";

    public static final String EXIT_BACKFILL = "补录";
    public static final String EXIT_RED_FLUSH = "红冲";

    /** 所属核对记录 */
    private Long checkId;
    /** 类型:账户/平台/应付 */
    private String itemType;
    /** 账户 id 或 供应商 id */
    private Long refId;
    /** 账户名/供应商名(快照留档) */
    private String refName;
    /** 系统数(账户=期初+Σ流水;应付=Σ正常实结−Σ红字−Σ已付款) */
    private BigDecimal systemAmount;
    /** 实际数(手填),NULL=未核对 */
    private BigDecimal actualAmount;
    /** 差异 = 实际 − 系统 */
    private BigDecimal diffAmount;
    /** 账户差异出口:生成的资金调整单 */
    private Long adjustDocId;
    /** 应付差异出口:补录/红冲 */
    private String exitAction;
    /** 应付行红冲跳转目标:该供应商最近来源单据 */
    private Long sourceDocId;
    /** 差异说明(差异行没走出口时必填留痕) */
    private String note;
}
