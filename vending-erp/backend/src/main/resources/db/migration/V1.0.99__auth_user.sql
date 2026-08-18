-- 2026-08-19 邀请码自助注册 + 账号密码登录（全系统统一契约 · dev-standards《邀请码注册-全系统盘点与落地方案》）
-- 占位头 X-User-* 由本表账号 + Bearer token 派生（ADR-001 "可信网关重注入" 在应用内落地）
CREATE TABLE IF NOT EXISTS yc_vend_auth_user (
  id              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  username        varchar(64)   NOT NULL COMMENT '登录账号(唯一)',
  password_hash   varchar(255)  NOT NULL COMMENT 'PBKDF2-HMAC-SHA256 哈希(iter:salt:hash)',
  display_name    varchar(64)   NOT NULL COMMENT '显示名(=占位头 X-User-Name)',
  role            varchar(32)   NOT NULL COMMENT '角色(=占位头 X-User-Role:老板/财务/供应链/业务/LP)',
  status          tinyint       NOT NULL DEFAULT 1 COMMENT '1 正常 0 停用',
  created_at      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at   datetime      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rent_auth_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号(邀请码注册,2026-08-19)';
