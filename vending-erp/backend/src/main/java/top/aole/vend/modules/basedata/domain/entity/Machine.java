package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 机器档案(yc_vend_machine)。
 * device_id(后台设备 ID)租户内唯一,是与 fanmaiji.top 对齐的锚点;撤点先退库再停用,历史保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_machine")
public class Machine extends BaseEntity {

    /** 机器编号(内部,租户内唯一) */
    private String machineCode;

    /** 机器名称(1楼售卖机/纸箱厂…) */
    private String machineName;

    /** 后台设备 ID(租户内唯一) */
    private String deviceId;

    /** 点位描述 */
    private String location;

    /** 机型 */
    private String model;

    /** 货道数 */
    private Integer slotCount;

    /** 负责人(SSO 用户 ID) */
    private Long managerUser;

    /** 机器状态:在线/故障/停用 */
    private String machineStatus;

    /** 上线日期 */
    private LocalDate onlineDate;

    private String remark;
}
