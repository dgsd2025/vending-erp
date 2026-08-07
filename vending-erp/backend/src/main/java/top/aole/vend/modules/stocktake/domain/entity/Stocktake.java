package top.aole.vend.modules.stocktake.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 盘点单(yc_vend_stocktake,M2-4)。
 *
 * 交互契约(调研报告 §7.3 问1):选范围(仓库/机器)→ 系统快照账面数 → 只录有差异的实盘
 * (未录=视同相符)→ 差异行必选原因 → 确认自动生成盘盈入库/盘亏出库单(复用单据通道)。
 * 机器盘点确认后必落快照锚点(snapshot_source=盘点)——机器推算账的校准机制(M1-9 缺锚点自愈)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_stocktake")
public class Stocktake extends BaseEntity {

    public static final String SCOPE_WAREHOUSE = "仓库";
    public static final String SCOPE_MACHINE = "机器";

    public static final String ST_IN_PROGRESS = "进行中";
    public static final String ST_PENDING = "待确认";
    /** 确认抢占中间态(盲审 P1-1):confirm 开头条件 UPDATE 抢到才继续;事务内瞬态,提交时变已完成/回滚回待确认 */
    public static final String ST_CONFIRMING = "处理中";
    public static final String ST_DONE = "已完成";
    public static final String ST_VOID = "已作废";

    /** 盘点单号:PD-yyyyMMdd-三位流水 */
    private String stNo;

    /** 范围:仓库/机器 */
    private String scopeType;

    /** 机器(scope=机器时) */
    private Long machineId;

    /** 账面快照时间(创建时系统自动快照账面数) */
    private LocalDateTime snapshotTime;

    /** 状态:进行中/待确认/已完成/已作废(单笔差异成本额>¥50 需老板确认) */
    private String stStatus;

    /** 确认生成的盘盈入库单 */
    private Long gainDocId;

    /** 确认生成的盘亏出库单(确认时强制走五步向导) */
    private Long lossDocId;

    /** 来源:月度SOP任务包/补货顺手盘/手动 */
    private String sourceTask;
}
