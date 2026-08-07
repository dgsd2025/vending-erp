-- M1-3 导入中心:sale_record 补条码原值列
-- 背景:fanmaiji「出货明细」导出没有商品编号列(数据字典 1.10 实测),
--   通道1 别名匹配以 条码 为主、名称兜底(DESIGN_DOC 遗留裁决点#2)。
--   待绑定行(product_id=NULL)绑定后要"重处理回补",必须留住原始条码,
--   否则只有 alias_code_raw(编号,恒空)+ alias_name_raw 可用,条码主键退化。
ALTER TABLE yc_vend_sale_record
  ADD COLUMN alias_barcode_raw varchar(64) DEFAULT NULL COMMENT '后台商品条形码原值(通道1别名匹配主键:条码为主/名称兜底;重处理回补用)' AFTER alias_code_raw,
  ADD INDEX idx_sale_barcode_raw (alias_barcode_raw);
