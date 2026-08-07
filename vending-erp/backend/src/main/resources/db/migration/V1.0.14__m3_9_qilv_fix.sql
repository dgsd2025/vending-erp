-- =====================================================================
-- M3-9 七律审计修复(P1×5):
-- ① 逆向出口:付款单/支出单补「红冲」指针列(红字负项行承接,不改写历史);
-- ② 任务种子:平台结算核对(每月2日)/ 月度钱盘(每月1日)——兑现前端「⚡自动任务」承诺;
--    check_type 对应 TaskService.runCheck 的 EXISTS 校验(完成必有系统校验,铁律#8)。
-- =====================================================================

-- 1. 付款单红冲通道:red_flush_of 指向被红冲的原付款单;
--    红冲行 = 负额付款(pay_time 非空 → Σ已付款自动回落),原单状态 → 已红冲(历史不改写)
ALTER TABLE yc_vend_payment
  ADD COLUMN red_flush_of bigint DEFAULT NULL
    COMMENT '红冲指向的原付款单ID(本行=负额红冲行);原单被红冲后 pay_status=已红冲' AFTER remark,
  MODIFY pay_status varchar(16) NOT NULL DEFAULT '待付款'
    COMMENT '状态:待付款/确认中/已付款/结算完成/差异挂起/已作废(仅待付款可作废)/已红冲(钱已动的唯一逆向)/红冲(负额红冲行)';

-- 2. 支出单红冲通道(同尺):待确认可作废;已完成唯一逆向=红冲(反向流水+设备台账行标退回)
ALTER TABLE yc_vend_expense
  ADD COLUMN red_flush_of bigint DEFAULT NULL
    COMMENT '红冲指向的原支出单ID(本行=负额红冲行);原单被红冲后 exp_status=已红冲' AFTER remark,
  MODIFY exp_status varchar(16) NOT NULL DEFAULT '待确认'
    COMMENT '状态:待确认/确认中/已完成/已作废(仅待确认)/已红冲(已确认的唯一逆向)/红冲(负额红冲行)';

-- 3. 任务种子×2(兑现 Money.vue「⚡每月2日结算核对 / ⚡每月1日钱盘」两处承诺):
--    SETTLEMENT  = 当月存在已核销/已核对平台结算单(真核销才算完,不是打勾就算);
--    CASH_CHECK  = 当月存在已完成钱盘核对记录(钱盘收口时主动触发自动打勾)。
INSERT INTO yc_vend_routine_task
  (task_key, task_name, cycle_rule, cycle_type, cycle_value, anchor_date,
   assignee_role, check_type, auto_check_rule, owner_role)
VALUES
  ('settlement_check', '平台结算核对(录单+凭证+核销)', '每月2日', '每月', 2, NULL,
   'FINANCE', 'SETTLEMENT', '当月存在已核销/已核对结算单(系统真核销才算完)', 'FINANCE'),
  ('monthly_cash_check', '月度钱盘三核对(账户/平台到账/应付)', '每月1日', '每月', 1, NULL,
   'BOSS', 'CASH_CHECK', '当月存在已完成钱盘核对记录(收口即自动打勾)', 'BOSS');
