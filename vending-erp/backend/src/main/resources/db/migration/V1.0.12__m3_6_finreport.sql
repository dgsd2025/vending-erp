-- M3-6 资产快照 + 简版利润表:
-- yc_vend_asset_snapshot 建表已在 V1.0.0(P0-6 公式字段 claim_receivable/net_asset 在位),本票不再建表。
-- 唯一增量:平台结算单补 lock_diff_note 通道列(P0-2 锁账×补导)——
-- "已核销结算单区间"只存在于 yc_vend_settlement(period_start/period_end);
-- 锁后补导命中已核销区间 → 只写本列提示,stl_status 永不回退(与 settle_bill.lock_diff_note 同一口径)。
ALTER TABLE yc_vend_settlement
  ADD COLUMN lock_diff_note varchar(500) DEFAULT NULL
    COMMENT '锁后新增差异提示(P0-2):已核销结算单区间遇锁账后补导,仅提示不改状态'
    AFTER stl_status;
