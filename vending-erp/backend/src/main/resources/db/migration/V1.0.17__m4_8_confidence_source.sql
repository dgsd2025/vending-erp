-- M4-8 七律修复 P1-1:置信分来源诚实标注
-- 背景:🔬 弹窗宣称「确信分=规则真算」,但部分 AI 挂点的置信分是固定基准(规则未覆盖)。
-- 为不宣称真算却写死,给每条 AI 调用记录标注置信分来源:
--   computed = 规则真算(相似度 / 偏离度 / 字段完备度 / 模板匹配度 / 数据完备度)
--   fixed    = 固定基准置信(规则未覆盖,给一个保守基准档)
ALTER TABLE yc_vend_llm_call_log
  ADD COLUMN confidence_source varchar(16) DEFAULT NULL
  COMMENT '置信分来源:computed=规则真算/fixed=固定基准置信(规则未覆盖)' AFTER confidence;
