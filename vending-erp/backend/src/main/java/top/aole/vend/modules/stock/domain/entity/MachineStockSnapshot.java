package top.aole.vend.modules.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 机器库存快照(yc_vend_machine_stock_snapshot):锚点+增量推算法的锚点表。
 * 口径(P2-12/穿行场景11):机器实时库存=最近快照+快照之后按业务时间戳的增量(转移+/销售-),与导入顺序无关。
 */
@Data
@TableName("yc_vend_machine_stock_snapshot")
public class MachineStockSnapshot {

    public static final String SRC_BACKEND_PAGE = "后台缺货页";
    public static final String SRC_STOCKTAKE = "盘点";
    public static final String SRC_REPLENISH = "补货记录";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long machineId;
    private Long productId;
    private String slotNo;
    /** 快照业务时间 */
    private LocalDateTime snapshotTime;
    private BigDecimal qty;
    /** 来源:后台缺货页/盘点/补货记录 */
    private String snapshotSource;

    private Long createUser;
    private Long updateUser;
}
