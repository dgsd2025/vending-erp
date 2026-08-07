-- M3-9 盲审修复:
-- P1-4/P1-5 非现金过账通道:cash_flow.account_id 允许 NULL——
--   NULL = 非现金流水(成本调整已售Δ / 结算差异收口留痕),只进 pl_line 利润表聚合,
--   不属于任何账户,余额查询(WHERE account_id=?)天然排除,不动任何账户余额。
ALTER TABLE yc_vend_cash_flow
  MODIFY account_id bigint DEFAULT NULL
  COMMENT '账户(NULL=非现金过账:成本调整已售Δ/结算差异收口,只进利润表行不动账户余额)';
