-- M2-1/M2-2 补货引擎:补货参数补三列(慢销 min/max 箱数 + AI 解释模型名)
-- 慢销品(日均<1.5)不套 (R,S) 公式,直接 min/max:低于 min 补到 max(skill 铁律#2)
ALTER TABLE yc_vend_replenish_config
  ADD COLUMN slow_min_boxes decimal(8,3) NOT NULL DEFAULT 0.5 COMMENT '慢销品最低水位(箱):仓库+机内+在途低于此值触发补货(日均<1.5不套公式)' AFTER expire_warn_days,
  ADD COLUMN slow_max_boxes decimal(8,3) NOT NULL DEFAULT 1 COMMENT '慢销品补到水位(箱):触发后补到此值' AFTER slow_min_boxes,
  ADD COLUMN llm_model varchar(64) DEFAULT NULL COMMENT 'AI解释模型名(可配不写死;mock阶段仅展示,空=跟随llm.model配置)' AFTER slow_max_boxes;
