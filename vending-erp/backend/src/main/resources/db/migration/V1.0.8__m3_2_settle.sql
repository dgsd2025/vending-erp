-- M3-2 应付供应商全链:结算单差异说明 + 红字冲抵回写位 + 状态口径补注
-- (表 settle_bill/payment/deduction 均已在 V1.0.0 建好,本迁移只补链路运转需要的两列与状态注释)

-- 1. 结算单:差异处理说明(凭证金额≠结算单金额 → 差异挂起,补说明后闭环留痕)
ALTER TABLE yc_vend_settle_bill
  ADD COLUMN diff_note varchar(255) DEFAULT NULL COMMENT '差异处理说明(P9.2:凭证金额≠结算单金额→挂起,补说明或改单后闭环)' AFTER lock_diff_note;

-- 2. 结算单:红字被用于哪张正常单(红冲连锁:已付款采购红冲→红字冲抵下一单,回写位)
ALTER TABLE yc_vend_settle_bill
  ADD COLUMN offset_into_bill_id bigint DEFAULT NULL COMMENT '红字专用:本红字被冲抵进哪张正常结算单(§13.2-1 冲抵下一单)' AFTER diff_note;

-- 3. 状态口径补注(varchar 值域扩展,不动数据):
--    正常单:待确认/待付款/已付款/已完成/差异挂起/已作废(红冲未付款单→作废并释放抵扣)
--    红字单:待冲抵/已冲抵(红冲已付款单→生成红字,不动已完成付款)
ALTER TABLE yc_vend_settle_bill
  MODIFY COLUMN bill_status varchar(16) NOT NULL DEFAULT '待确认'
  COMMENT '状态:正常单 待确认/待付款/已付款/已完成/差异挂起/已作废;红字单 待冲抵/已冲抵(M3-2)';

-- 4. 抵扣确认单状态口径补注:已抵扣的落值为「已抵扣」,用于哪张结算单见 used_settle_bill_id
ALTER TABLE yc_vend_deduction
  MODIFY COLUMN ded_status varchar(16) NOT NULL DEFAULT '待抵扣'
  COMMENT '状态:待抵扣/已抵扣(已用于结算单X,X=used_settle_bill_id;红冲释放回待抵扣)/作废';
