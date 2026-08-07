package top.aole.vend.modules.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水(yc_vend_stock_ledger):唯一由单据产生的两级库存账。
 * 期初/期末/进销存汇总全部由流水推算,不落静态余额表。
 */
@Data
@TableName("yc_vend_stock_ledger")
public class StockLedger {

    public static final String LOC_WAREHOUSE = "仓库";
    public static final String LOC_MACHINE = "机器";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long productId;
    /** 账本:仓库/机器(机器侧权威在后台,本表为经营侧推算账) */
    private String locationType;
    private Long machineId;
    /** 来源单据头:库存流水唯一由单据产生,禁止直改库存 */
    private Long docId;
    private Long docItemId;
    /** 变动数量(+入/-出) */
    private BigDecimal changeQty;
    /** 变动后结存 */
    private BigDecimal balanceQty;
    /** 移动加权单位成本快照(M1-5 先落单据单价,加权引擎 M1-6 采购模块接上;无采购史=NULL) */
    private BigDecimal unitCost;
    private BigDecimal amount;
    /** 业务时间戳(乱序导入按此重算,不按写入顺序) */
    private LocalDateTime bizTime;

    private Long createUser;
    private Long updateUser;
}
