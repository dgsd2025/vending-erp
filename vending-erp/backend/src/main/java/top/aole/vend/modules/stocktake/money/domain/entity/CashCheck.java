package top.aole.vend.modules.stocktake.money.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 月度钱盘核对记录(yc_vend_cash_check,§8.2 月度 SOP 的 D2)。
 *
 * 三核对分区(明细行 item_type):
 * ① 账户核对:真实账户 系统余额 vs 实际余额(差异出口=资金调整单,唯一出口);
 * ② 平台到账核对:结算模式 UNSET 时整块跳过(platform_skipped=1,横幅原文留档);
 * ③ 应付核对:供应商 系统余额 vs 对方账(不符出口=补录/红冲两按钮,只跳转不重造)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_cash_check")
public class CashCheck extends BaseEntity {

    public static final String ST_IN_PROGRESS = "进行中";
    public static final String ST_DONE = "已完成";
    public static final String ST_VOID = "已作废";

    /** 核对单号 QP-yyyyMMdd-序号 */
    private String checkNo;
    /** 核对月份 yyyy-MM */
    private String checkPeriod;
    /** 状态:进行中/已完成/已作废 */
    private String checkStatus;
    /** 快照时结算模式(UNSET/PLATFORM/DIRECT) */
    private String settleModeSnap;
    /** 平台到账核对是否跳过(结算模式待核实) */
    private Boolean platformSkipped;
    /** 平台核对说明(UNSET 横幅原文留档) */
    private String platformNote;
    private String remark;
    /** 完成确认人 */
    private Long confirmBy;
    private LocalDateTime confirmAt;
}
