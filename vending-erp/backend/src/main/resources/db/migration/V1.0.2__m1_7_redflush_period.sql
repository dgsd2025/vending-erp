-- M1-7 红冲连锁 + 成本调整 + 锁账×补导(审计 P0-1 / P0-2)
-- 1) doc_head 补 pl_line:成本调整单"已售部分"进利润表的行标记(M3 报表实装取数;M1-7 先落标记)。
--    口径(附录C):未售部分 → stock_ledger 插 qty=0/amount=Δ 调整流水;
--    已售部分不追溯成本,金额记入单据备注 + pl_line='成本调整',由利润表"成本调整"行承接。
ALTER TABLE yc_vend_doc_head
  ADD COLUMN pl_line varchar(32) DEFAULT NULL COMMENT '利润表行标记(成本调整单已售部分=成本调整,M3 报表实装;cash_flow.pl_line 同族)' AFTER book_period;
