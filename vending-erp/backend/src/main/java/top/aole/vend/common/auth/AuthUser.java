package top.aole.vend.common.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 账号表 yc_vend_auth_user（2026-08-19 邀请码注册） */
@Data
@TableName("yc_vend_auth_user")
public class AuthUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
}
