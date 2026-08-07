-- M3-1 钱账基座:全局配置表(首个键 settle.mode,附录D 结算双模式)
-- settle.mode = UNSET(默认,待核实横幅)/ PLATFORM(平台归集)/ DIRECT(微信直连)
CREATE TABLE yc_vend_config (
  id           bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id    varchar(64)  NOT NULL DEFAULT '000000' COMMENT '租户ID',
  config_key   varchar(64)  NOT NULL COMMENT '配置键(如 settle.mode)',
  config_value varchar(255) DEFAULT NULL COMMENT '配置值',
  remark       varchar(255) DEFAULT NULL COMMENT '说明',
  create_user  bigint       DEFAULT NULL COMMENT '创建人',
  create_dept  bigint       DEFAULT NULL COMMENT '创建部门',
  create_time  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user  bigint       DEFAULT NULL COMMENT '更新人(改动写 op_log)',
  update_time  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status       tinyint(1)   NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted   tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (tenant_id, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局配置:键值对(settle.mode 等),改动必写 op_log';
