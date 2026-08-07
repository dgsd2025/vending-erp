package top.aole.vend.modules.basedata.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 基础字段:每张表必备的公共列。
 * create_time / update_time / tenant_id 等由数据库默认值维护,插入时留空即可。
 * is_deleted 为全局逻辑删除字段(application.yml 里 logic-delete-field: isDeleted)。
 */
@Data
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Long createUser;

    private Long createDept;

    private LocalDateTime createTime;

    private Long updateUser;

    private LocalDateTime updateTime;

    /** 记录状态:1 正常 0 禁用 */
    private Integer status;

    /** 逻辑删除:0 否 1 是(全局逻辑删除字段) */
    private Integer isDeleted;
}
