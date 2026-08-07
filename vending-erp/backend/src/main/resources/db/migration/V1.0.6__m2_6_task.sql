-- =====================================================================
-- M2-6 任务日历:固定任务引擎(定义扩列)+ 今日任务实例(懒生成物化)+ 种子任务
-- 版本号取 1.0.6 对齐票号 M2-6,给并行票(补货/盘点)留 1.0.3-1.0.5 空位。
-- =====================================================================

-- 1. 任务定义扩列:引擎所需的结构化周期/责任人/系统校验字段
--    (原 cycle_rule/owner_role 保留作人话展示;due_period/task_status/last_result 为旧占位列,引擎不再使用)
ALTER TABLE yc_vend_routine_task
  ADD COLUMN cycle_type        varchar(16)  NOT NULL DEFAULT '每日' COMMENT '周期类型:每日/每周/每月/每N天' AFTER cycle_rule,
  ADD COLUMN cycle_value       int          NOT NULL DEFAULT 1 COMMENT '周期值:每周N(1-7,周一=1)/每月N日/每N天的N;每日忽略' AFTER cycle_type,
  ADD COLUMN anchor_date       date         DEFAULT NULL COMMENT '频率锚点日(每N天用:从该日起每N天到期)' AFTER cycle_value,
  ADD COLUMN assignee_role     varchar(16)  DEFAULT NULL COMMENT '责任角色:BOSS/FINANCE/REPLENISH/CLERK(铁律#8:任务绑角色)' AFTER anchor_date,
  ADD COLUMN assignee_user_id  bigint       DEFAULT NULL COMMENT '指定责任人ID(可空=角色内默认:取该角色第一个在岗的人)' AFTER assignee_role,
  ADD COLUMN assignee_user_name varchar(64) DEFAULT NULL COMMENT '指定责任人姓名快照' AFTER assignee_user_id,
  ADD COLUMN check_type        varchar(32)  DEFAULT NULL COMMENT '系统校验类型:IMPORT_BATCH当日成功导入/REPLENISH当日出库上架或建议全处理/STOCKTAKE当月已完成盘点单;NULL=纯手动任务' AFTER assignee_user_name,
  ADD COLUMN task_enabled      tinyint(1)   NOT NULL DEFAULT 1 COMMENT '是否启用(停用不再生成实例,历史实例保留)' AFTER check_type;

-- 2. 今日任务实例:按定义懒生成物化(查询当日视图时按日期幂等生成,无需 cron 进程)
CREATE TABLE yc_vend_task_instance (
  id                 bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id          varchar(64)  NOT NULL DEFAULT '000000' COMMENT '租户ID',
  task_id            bigint       NOT NULL COMMENT '任务定义ID(yc_vend_routine_task)',
  task_key           varchar(32)  NOT NULL COMMENT '任务键快照',
  task_name          varchar(64)  NOT NULL COMMENT '任务名快照',
  task_date          date         NOT NULL COMMENT '任务日期(实例按日物化)',
  assignee_role      varchar(16)  DEFAULT NULL COMMENT '责任角色快照',
  assignee_user_id   bigint       DEFAULT NULL COMMENT '当前责任人ID(生成时解析:定义指定人>角色内默认;转派后更新)',
  assignee_user_name varchar(64)  DEFAULT NULL COMMENT '当前责任人姓名(转派后更新,历次转派 op_log 留痕)',
  instance_status    varchar(16)  NOT NULL DEFAULT '待办' COMMENT '状态:待办/已完成/逾期(逾期未完成→次日红灯)',
  check_type         varchar(32)  DEFAULT NULL COMMENT '系统校验类型快照',
  done_type          varchar(16)  DEFAULT NULL COMMENT '完成方式:系统校验(自动打勾✅)/手动补标(未过系统校验,黄标🟡)',
  done_time          datetime     DEFAULT NULL COMMENT '完成时间',
  done_by            varchar(64)  DEFAULT NULL COMMENT '完成人(系统校验=系统)',
  done_note          varchar(255) DEFAULT NULL COMMENT '完成备注(手动补标必填留痕)',
  transfer_count     int          NOT NULL DEFAULT 0 COMMENT '转派次数(from/to/时间/原因全在 op_log)',
  create_user        bigint       DEFAULT NULL COMMENT '创建人',
  create_dept        bigint       DEFAULT NULL COMMENT '创建部门',
  create_time        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user        bigint       DEFAULT NULL COMMENT '更新人',
  update_time        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status             tinyint(1)   NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted         tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_inst (tenant_id, task_id, task_date),
  KEY idx_inst_date (tenant_id, task_date, instance_status),
  KEY idx_inst_assignee (assignee_user_name, task_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务实例:任务日历/今日工作台的数据源;完成必有系统校验,不是打勾就算(铁律#8)';

-- 3. 内置种子任务(引擎三件套;check_type 对应 TaskService 里的 EXISTS 校验 SQL)
INSERT INTO yc_vend_routine_task
  (task_key, task_name, cycle_rule, cycle_type, cycle_value, anchor_date,
   assignee_role, check_type, auto_check_rule, owner_role)
VALUES
  ('daily_import', '数据迁移导入(出货+补货记录)', '每日', '每日', 1, NULL,
   'CLERK', 'IMPORT_BATCH', '当日存在成功 import_batch(系统真收到文件才算完)', 'CLERK'),
  ('replenish_patrol', '补货巡检(照建议 pre-kit 上架)', '每3天', '每N天', 3, CURRENT_DATE,
   'REPLENISH', 'REPLENISH', '当日有已确认出库上架单,或当日补货建议全处理', 'REPLENISH'),
  ('monthly_stocktake', '月度盘点(货盘 SOP)', '每月1日', '每月', 1, NULL,
   'BOSS', 'STOCKTAKE', '当月存在已完成盘点单(盘点模块并行开发,校验为 EXISTS SQL)', 'BOSS');
