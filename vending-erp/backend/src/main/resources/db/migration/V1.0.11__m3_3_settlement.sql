-- M3-3 平台结算双模式(DESIGN_DOC 附录D):yc_vend_settlement 补列 + 状态口径改写
-- 表本体 V1.0.0 已建;本迁移只加"模式快照/确认留痕/差异复核"四列并把状态注释改成双模式口径。
-- 双模式:PLATFORM=平台归集(结算单核销+两差对账,§7.3 问3)| DIRECT=微信/支付宝直连(商户账单核对单,
-- 只对差不落收入流水——钱已直连入账,真实到账靠 M3-5 钱盘账户核对对总,避免双记)| UNSET=只出假设对比预览。

ALTER TABLE yc_vend_settlement
  ADD COLUMN mode_snap  varchar(16)  DEFAULT NULL COMMENT '录入时结算模式快照(PLATFORM=平台结算单/DIRECT=商户账单核对单):确认时必须与当前模式一致,改模式后旧单只能作废重录' AFTER stl_status,
  ADD COLUMN confirm_by varchar(64)  DEFAULT NULL COMMENT '确认经手人(X-User-Name 占位,SSO 后替换)' AFTER mode_snap,
  ADD COLUMN confirm_at datetime     DEFAULT NULL COMMENT '确认时间' AFTER confirm_by,
  ADD COLUMN diff_note  varchar(500) DEFAULT NULL COMMENT '差异挂起复核说明(resolve 留痕:漏单差/扣款差超阈值后人工核实的结论)' AFTER confirm_at;

ALTER TABLE yc_vend_settlement
  MODIFY COLUMN stl_status varchar(16) NOT NULL DEFAULT '待核对'
  COMMENT '状态:待核对 → 已核销(PLATFORM 确认:快照+两差+回填sale_record.settlement_id+两笔流水)/ 已核对(DIRECT 确认:只对差不动钱)/ 差异挂起(两差任一超阈值±1元,resolve 补说明后收口)/ 已作废(仅待核对可作废)';
