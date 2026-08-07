-- =====================================================================
-- M4-3 PDCA 改进循环闭环(§8.4:一张表 + 一个页面)
-- action_item 激活:验证指标从"一句话文本"升级为 结构化三件套(指标键+目标值+比较方向),
-- 到期自动回查才有可依据的机器口径;verify_metric 旧字段保留作人话展示。
-- =====================================================================

ALTER TABLE yc_vend_action_item
  ADD COLUMN metric_key     varchar(64)   DEFAULT NULL COMMENT '验证指标键(PdcaMetricService 注册表);NULL=需人工判定' AFTER verify_metric,
  ADD COLUMN metric_param   varchar(64)   DEFAULT NULL COMMENT '指标参数(如损耗原因"过期报损"/商品ID),配合 metric_key' AFTER metric_key,
  ADD COLUMN target_value   decimal(12,2) DEFAULT NULL COMMENT '目标值(与指标当前值比较)' AFTER metric_param,
  ADD COLUMN compare_op     varchar(4)    NOT NULL DEFAULT '<=' COMMENT '比较方向:<=(降到目标以下算通过)/ >=(升到目标以上算通过)' AFTER target_value,
  ADD COLUMN baseline_value decimal(12,2) DEFAULT NULL COMMENT '登记时指标基线值(改进前的起点,报告用)' AFTER compare_op,
  ADD COLUMN verify_value   decimal(12,2) DEFAULT NULL COMMENT '回查时指标实际值(自动回查落此)' AFTER baseline_value,
  ADD COLUMN verify_result  varchar(16)   DEFAULT NULL COMMENT '回查结果:通过/未达/需人工判定' AFTER verify_value,
  ADD COLUMN verify_note    varchar(255)  DEFAULT NULL COMMENT '回查说明(自动生成:当前值 vs 目标;需人工时写原因)' AFTER verify_result,
  ADD COLUMN verified_at    datetime      DEFAULT NULL COMMENT '最近一次回查时间' AFTER verify_note;
