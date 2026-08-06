package top.aole.vend.modules.task.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;

/**
 * 人员角色映射(yc_vend_user_role,§11.2 轻角色):SSO 前手工维护(人名字符串+角色枚举)。
 * 单人模式判定的数据源:所有角色映射到同一个人 → 任务日历列合并;加人自动拆列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_user_role")
public class UserRole extends BaseEntity {

    public static final String ROLE_BOSS = "BOSS";
    public static final String ROLE_FINANCE = "FINANCE";
    public static final String ROLE_REPLENISH = "REPLENISH";
    public static final String ROLE_CLERK = "CLERK";

    /** 园区主系统 SSO 用户ID(SSO 前手工维护填 0) */
    private Long userId;
    /** 姓名快照 */
    private String userName;
    /** BOSS/FINANCE/REPLENISH/CLERK */
    private String roleCode;
}
