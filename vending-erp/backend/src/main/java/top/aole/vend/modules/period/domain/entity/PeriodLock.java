package top.aole.vend.modules.period.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 锁账记录(yc_vend_period_lock,P0-2)。
 * 语义:锁定某 YYYY-MM 及**之前所有月份**;锁账线 = 未删记录里的最大 period。
 * 解锁 = 逻辑删除最新一条锁记录(锁账线回落到上一条),限老板角色(占位)+强制备注。
 * 规则:锁账只管改单/红冲不管补导——补导走 book_period=当前月,旧报表永不重算。
 */
@Data
@TableName("yc_vend_period_lock")
public class PeriodLock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    /** 锁定月 YYYY-MM(该月及之前全部锁定) */
    private String period;
    private LocalDateTime lockedAt;
    /** 锁账人(老板/财务) */
    private Long lockedBy;
    private String lockNote;

    private Long createUser;
    private Long updateUser;
    /** 逻辑删除:解锁=置1(留痕靠 op_log) */
    @TableLogic
    private Integer isDeleted;
}
