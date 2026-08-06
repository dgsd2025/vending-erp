package top.aole.vend.modules.prekit.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Pre-kit 配货单(yc_vend_prekit_ticket,P2-12 落表):一机一箱,按机器分组拣货装箱。
 *
 * 状态流:已生成 → 已执行(仓库装箱出发)→ 已核销/有差异。
 * 核销 = 次日(±48h 窗口)通道2导入的转移单按 机器×SKU 匹配:带出量 vs 实际上架量,
 * 差量 = 带回;takeback_rate = Σ带回/Σ带出 —— 补货 PDCA 核心指标的唯一数据生产者。
 * 超窗未核销亮黄灯(计算字段,不落库)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_prekit_ticket")
public class PrekitTicket extends BaseEntity {

    public static final String STATUS_CREATED = "已生成";
    public static final String STATUS_EXECUTED = "已执行";
    public static final String STATUS_VERIFIED = "已核销";
    public static final String STATUS_DIFF = "有差异";

    /** 核销匹配窗口(±天数):配货日与导入转移单业务日期相差 ≤2 天(±48h)才匹配 */
    public static final int VERIFY_WINDOW_DAYS = 2;

    /** 配货单号(PH-yyyyMMdd-三位流水) */
    private String ticketNo;

    /** 目标机器(一机一箱) */
    private Long machineId;

    /** 配货日期 */
    private LocalDate planDate;

    /** 状态:已生成/已执行/已核销/有差异 */
    private String ticketStatus;

    /** 核销匹配的转移单(doc_head,取窗口内首张) */
    private Long verifyDocId;

    /** 带回率 = Σ带回/Σ带出(核销时算) */
    private BigDecimal takebackRate;

    /** 执行人(仓库装箱出发) */
    private Long execBy;

    /** 执行时间 */
    private LocalDateTime execAt;

    /** 核销时间 */
    private LocalDateTime verifyAt;
}
