-- M3-4 索赔 + 杂费/设备 + 线下收入复合单:两列小补
-- ① 索赔单加备注列:放弃必填(放弃原因留痕),申请说明可选(§9.3 场景4)
ALTER TABLE yc_vend_claim
  ADD COLUMN remark varchar(255) DEFAULT NULL COMMENT '备注:放弃时必填(放弃原因留痕);申请时可选说明' AFTER cash_flow_id;

-- ② 支出单加设备名列:类别=设备购置时录入即填,确认落流水时据此建 equipment 台账行(§9.3 场景8)
ALTER TABLE yc_vend_expense
  ADD COLUMN equip_name varchar(64) DEFAULT NULL COMMENT '设备名称(类别=设备购置时必填;确认时同步建 yc_vend_equipment 台账行)' AFTER is_equipment;
