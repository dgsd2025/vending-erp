-- M3-5 资金调整单 + 月度钱盘三核对(穿行审计 P1-7:钱盘差异的唯一出口)
-- 版本号取 1.0.10:1.0.8/1.0.9 留给并行票(M3-2 付款 / M3-4 结算);flyway out-of-order 已开。

-- 1. 资金调整单扩展(doc_head 通道之外的钱字段;doc_type=资金调整 已在十枚举)
CREATE TABLE yc_vend_cash_adjust (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id     varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  doc_id        bigint        NOT NULL COMMENT '关联资金调整单(yc_vend_doc_head,doc_type=资金调整)',
  account_id    bigint        NOT NULL COMMENT '调整哪个账户(仅真实账户;虚拟账户不可手工收支)',
  direction     varchar(4)    NOT NULL COMMENT '方向:收(实际多于系统/盘盈)/支(实际少于系统/盘亏)',
  amount        decimal(12,2) NOT NULL COMMENT '调整金额(恒正,方向看direction)',
  reason        varchar(32)   NOT NULL COMMENT '原因枚举:盘盈/盘亏/手续费漏记/期初错/其他(其他必备注)',
  cash_check_id bigint        DEFAULT NULL COMMENT '来源钱盘核对记录(从核对差异行一键生成时回链)',
  create_user   bigint        DEFAULT NULL COMMENT '创建人',
  create_dept   bigint        DEFAULT NULL COMMENT '创建部门',
  create_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user   bigint        DEFAULT NULL COMMENT '更新人',
  update_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status        tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除(单据不许删,随doc红冲/作废)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cash_adjust_doc (doc_id),
  KEY idx_cash_adjust_account (account_id),
  KEY idx_cash_adjust_check (cash_check_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金调整单扩展:账户/方向/金额/原因;确认后经 MoneyPostingEvent 落 cash_flow(P1-7)';

-- 2. 月度钱盘核对记录(§8.2 D2:账户/平台到账/应付 三核对,结果落档)
CREATE TABLE yc_vend_cash_check (
  id                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id         varchar(64)  NOT NULL DEFAULT '000000' COMMENT '租户ID',
  check_no          varchar(32)  NOT NULL COMMENT '核对单号 QP-yyyyMMdd-序号',
  check_period      char(7)      NOT NULL COMMENT '核对月份 yyyy-MM',
  check_status      varchar(16)  NOT NULL DEFAULT '进行中' COMMENT '状态:进行中/已完成/已作废',
  settle_mode_snap  varchar(16)  NOT NULL DEFAULT 'UNSET' COMMENT '快照时结算模式(UNSET/PLATFORM/DIRECT)',
  platform_skipped  tinyint(1)   NOT NULL DEFAULT 0 COMMENT '平台到账核对是否跳过(结算模式待核实=1)',
  platform_note     varchar(500) DEFAULT NULL COMMENT '平台核对说明(UNSET横幅原文留档)',
  remark            varchar(255) DEFAULT NULL COMMENT '备注',
  confirm_by        bigint       DEFAULT NULL COMMENT '完成确认人',
  confirm_at        datetime     DEFAULT NULL COMMENT '完成时间',
  create_user       bigint       DEFAULT NULL COMMENT '创建人',
  create_dept       bigint       DEFAULT NULL COMMENT '创建部门',
  create_time       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user       bigint       DEFAULT NULL COMMENT '更新人',
  update_time       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status            tinyint(1)   NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted        tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cash_check_no (tenant_id, check_no),
  KEY idx_cash_check_period (check_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='月度钱盘核对记录(§8.2 D2):三核对结果归档,差异出口=资金调整单/补录/红冲';

-- 3. 钱盘核对明细(三类行:账户/平台/应付)
CREATE TABLE yc_vend_cash_check_item (
  id             bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id      varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  check_id       bigint        NOT NULL COMMENT '所属核对记录',
  item_type      varchar(8)    NOT NULL COMMENT '类型:账户(真实账户)/平台(平台待结算虚账)/应付(供应商欠款)',
  ref_id         bigint        NOT NULL COMMENT '账户id 或 供应商id',
  ref_name       varchar(64)   DEFAULT NULL COMMENT '账户名/供应商名(快照留档)',
  system_amount  decimal(12,2) NOT NULL DEFAULT 0 COMMENT '系统数(账户=期初+Σ流水;应付=Σ正常实结-Σ红字-Σ已付款)',
  actual_amount  decimal(12,2) DEFAULT NULL COMMENT '实际数(手填:账户实际余额/供应商对方账),NULL=未核对',
  diff_amount    decimal(12,2) DEFAULT NULL COMMENT '差异=实际-系统(核对后计算)',
  adjust_doc_id  bigint        DEFAULT NULL COMMENT '账户差异出口:生成的资金调整单(唯一出口,防重复生成)',
  exit_action    varchar(16)   DEFAULT NULL COMMENT '应付差异出口:补录/红冲(只做跳转留痕,不重造)',
  source_doc_id  bigint        DEFAULT NULL COMMENT '应付行红冲跳转目标:该供应商最近的来源单据(结算单链)',
  note           varchar(255)  DEFAULT NULL COMMENT '差异说明(差异行没走出口时必填留痕)',
  create_user    bigint        DEFAULT NULL COMMENT '创建人',
  create_dept    bigint        DEFAULT NULL COMMENT '创建部门',
  create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user    bigint        DEFAULT NULL COMMENT '更新人',
  update_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status         tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_cci_check (check_id),
  KEY idx_cci_ref (item_type, ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱盘核对明细:系统数vs实际数逐行留档;账户差异唯一出口=资金调整单,应付差异出口=补录/红冲';
