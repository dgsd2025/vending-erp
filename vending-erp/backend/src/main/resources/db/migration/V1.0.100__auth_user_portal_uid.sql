-- 2026-08-19 平台门户 SSO（aole-portal · eco.vvaix.com 免登直达）：账号表加 portal_uid 绑定列
-- 场景 2（老子系统 + 本地用户表）：长期身份键固定 portal_uid（ole-portal-sso 硬约束 8），手机号仅用于首次绑定
-- 幂等：先查 INFORMATION_SCHEMA 再加列/加唯一索引，重跑不报错（生产由负责人回本机手动 apply，自动部署不跑 migration）
SET @tbl := 'yc_vend_auth_user';

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'portal_uid');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE yc_vend_auth_user ADD COLUMN portal_uid varchar(64) NULL DEFAULT NULL COMMENT ''平台门户 portal_uid(SSO 绑定,NULL=未绑定)'' AFTER last_login_at',
  'SELECT ''portal_uid already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'uk_vend_auth_user_portal_uid');
SET @sql := IF(@idx_exists = 0,
  'ALTER TABLE yc_vend_auth_user ADD UNIQUE KEY uk_vend_auth_user_portal_uid (portal_uid)',
  'SELECT ''uk_vend_auth_user_portal_uid already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
