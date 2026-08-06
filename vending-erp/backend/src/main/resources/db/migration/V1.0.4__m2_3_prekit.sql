-- M2-3 Pre-kit 配货单闭环:执行/核销留痕字段 + 明细行回链补货建议
-- 状态流:已生成 → 已执行(仓库装箱出发,记执行人/时间)→ 已核销/有差异(次日导入 ±48h 窗口匹配)
ALTER TABLE yc_vend_prekit_ticket
  ADD COLUMN exec_by   bigint   DEFAULT NULL COMMENT '执行人(仓库装箱出发标记已执行)' AFTER verify_doc_id,
  ADD COLUMN exec_at   datetime DEFAULT NULL COMMENT '执行时间' AFTER exec_by,
  ADD COLUMN verify_at datetime DEFAULT NULL COMMENT '核销时间(导入通道2触发)' AFTER exec_at;

-- 明细行回链补货建议行:生成→建议行标「已生成配货单」;执行→标「已执行」(plan_status 联动)
ALTER TABLE yc_vend_prekit_ticket_item
  ADD COLUMN plan_id bigint DEFAULT NULL COMMENT '来源补货建议行(yc_vend_replenish_plan);执行时回写建议状态' AFTER product_id;
