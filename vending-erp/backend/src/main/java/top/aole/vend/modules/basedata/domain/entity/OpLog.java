package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 操作日志(yc_vend_op_log)。全量不可删:任何写操作都要落一条(谁、几点、改了什么)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_op_log")
public class OpLog extends BaseEntity {

    /** 操作人(SSO 用户 ID;SSO 未接前为 0) */
    private Long userId;

    /** 操作人姓名快照(来自 X-User-Name 头,SSO 后替换) */
    private String userName;

    /** 动作:新建/修改/确认/红冲/导入/回滚/锁账/改参数等 */
    private String action;

    /** 对象类型(product/supplier/machine/slot/replenish_config/sku_alias…) */
    private String targetType;

    /** 对象 ID */
    private Long targetId;

    /** 改动前值(JSON) */
    private String beforeJson;

    /** 改动后值(JSON) */
    private String afterJson;

    /** 操作时间 */
    private LocalDateTime opTime;
}
