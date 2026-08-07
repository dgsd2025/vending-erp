-- =====================================================================
-- 智慧园区 · 园区自动售卖机 ERP · 全量 Schema 草案 (M1-1)
-- MySQL 8 · utf8mb4 · InnoDB
-- 依据: 调研报告 v1.8(§13 修正案效力最高) + 穿行审计报告(P0×6/P1/P2 全落地) + UI Mockup V15
--       + 冲刺0 事实(2026-08-06): 老账存在「一码多品」6组(SP009/010/011/012/046/069),
--         期初导入向导带编码冲突清洗步骤; sku_alias 以后台商品编号+条码为主关联键,不绑名称
-- 策略: 全量表一次建齐(M1-M4 的表都建), 功能分期实现
-- 规范:
--   * 表名前缀 yc_vend_, 全小写下划线
--   * 每表必备: id(自增bigint) / tenant_id / create_user / create_dept / update_user
--               / create_time / update_time / status(记录状态) / is_deleted
--   * 业务状态字段一律叫 xxx_status(varchar), 与必备字段 status(tinyint) 区分
--   * 金额 decimal(12,2) · 数量 decimal(12,3)(有整箱换算) · 单价 decimal(12,4) · 比率 decimal(8,4)
--   * 索引命名 uk_ / idx_
-- =====================================================================

SET NAMES utf8mb4;

-- =====================================================================
-- 一、主数据区 (M1 数据地基)
-- =====================================================================

-- 1. 商品档案
CREATE TABLE yc_vend_product (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  sku_code         varchar(32)   NOT NULL COMMENT 'SKU编码(沿用SP001体系;一码多品拆分后新码如SP009A/SP009B)',
  legacy_code      varchar(32)   DEFAULT NULL COMMENT '老账原编码(一码多品清洗留痕:SP009/010/011/012/046/069 六组同码挂两个商品,期初导入向导强制拆分,原码存此追溯)',
  product_name     varchar(128)  NOT NULL COMMENT '采购商品名(主名称)',
  barcode          varchar(64)   DEFAULT NULL COMMENT '条码',
  category         varchar(32)   DEFAULT NULL COMMENT '品类:饮料/泡面/面包/卤味/槟榔等',
  unit             varchar(16)   NOT NULL DEFAULT '件' COMMENT '基本单位(瓶/袋/盒)',
  box_spec         decimal(12,3) NOT NULL DEFAULT 1 COMMENT '箱规:每整箱含基本单位数(如24瓶/箱),整箱换算用',
  shelf_life_days  int           DEFAULT NULL COMMENT '保质期天数(方便面≈180,含乳饮料180-270)',
  ref_cost         decimal(12,4) DEFAULT NULL COMMENT '参考成本(P1-8):无采购史时毛利计算显示"—(成本待补)",禁止用0参与移动加权',
  ref_price        decimal(12,4) DEFAULT NULL COMMENT '参考售价:仅为参考价,真实收入以sale_record实收为准(§13.1毛利口径);后台改价由导入侦测更新并写price_log',
  product_status   varchar(16)   NOT NULL DEFAULT '在售' COMMENT '商品状态:在售/清仓中/停售。清仓中(P2-10):禁采购、禁进补货建议,允许上架/退货/报损;停售≠删除,有流水永不删',
  clearance_since  date          DEFAULT NULL COMMENT '进入清仓中的日期:仓库残余>0且超30天→三选一提示(退供/报损/换机促销)',
  min_display_qty  decimal(12,3) DEFAULT NULL COMMENT '机内上限建议(过期多时下调,PDCA改进参数)',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_user      bigint        DEFAULT NULL COMMENT '创建人(园区SSO用户ID)',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态:1正常 0禁用',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_sku (tenant_id, sku_code),
  KEY idx_product_barcode (barcode),
  KEY idx_product_status (tenant_id, product_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品档案:SKU主数据。口径:售价仅参考,毛利=实收-加权成本;ref_cost为无采购史兜底';

-- 2. SKU 别名映射(解决"两套名称"第一痛点)
CREATE TABLE yc_vend_sku_alias (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  alias_code       varchar(64)   NOT NULL DEFAULT '' COMMENT '后台商品编号(冲刺0拍板:主关联键之一,来自后台商品列表)',
  alias_barcode    varchar(64)   NOT NULL DEFAULT '' COMMENT '后台条码(主关联键之二;可空存空串保证唯一键生效)',
  alias_name       varchar(128)  NOT NULL COMMENT '后台销售商品名(如"达利园和其正600l"):仅留痕与展示,不参与唯一键——后台同名可挂不同商品,名称不可靠',
  product_id       bigint        NOT NULL COMMENT '归集到的采购SKU(yc_vend_product.id)',
  bind_source      varchar(16)   NOT NULL DEFAULT '人工' COMMENT '绑定来源:人工/AI建议采纳/商品列表导入',
  ai_confidence    decimal(8,4)  DEFAULT NULL COMMENT 'AI归集置信度(接入点#1)',
  llm_call_id      bigint        DEFAULT NULL COMMENT '关联AI调用记录(透明四件套)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态:1生效 0停用',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_alias (tenant_id, alias_code, alias_barcode),
  KEY idx_alias_name (alias_name),
  KEY idx_alias_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU别名映射:后台商品编号+条码→采购SKU(冲刺0拍板,不绑名称),导入自动归集,绑一次终身生效';

-- 3. 供应商档案
CREATE TABLE yc_vend_supplier (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  supplier_code    varchar(32)   NOT NULL COMMENT '供应商编码',
  supplier_name    varchar(64)   NOT NULL COMMENT '供应商名称(蔡彩云/陈老板/拼多多自购)',
  contact          varchar(64)   DEFAULT NULL COMMENT '联系方式(微信/电话)',
  settle_method    varchar(16)   NOT NULL DEFAULT '现结' COMMENT '结算方式:现结/月结/预付',
  account_days     int           NOT NULL DEFAULT 0 COMMENT '账期天数(月结N天,逾期首页亮黄灯)',
  opening_payable  decimal(12,2) NOT NULL DEFAULT 0 COMMENT '期初应付(上线向导录入,改动走调整留痕)',
  coop_status      varchar(16)   NOT NULL DEFAULT '合作中' COMMENT '合作状态:合作中/停用(停用保留历史往来)',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_code (tenant_id, supplier_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商档案。口径:应付余额=期初+Σ采购-Σ退货-Σ抵扣-Σ付款,实时算不落表';

-- 4. 机器档案
CREATE TABLE yc_vend_machine (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  machine_code     varchar(32)   NOT NULL COMMENT '机器编号(内部)',
  machine_name     varchar(64)   NOT NULL COMMENT '机器名称(1楼售卖机/4楼售卖机/7楼售卖机/纸箱厂)',
  device_id        varchar(64)   NOT NULL COMMENT '后台设备ID(如8CFCA017C113888),与fanmaiji.top对齐的主关联键',
  location         varchar(128)  DEFAULT NULL COMMENT '点位描述',
  model            varchar(64)   DEFAULT NULL COMMENT '机型',
  slot_count       int           DEFAULT NULL COMMENT '货道数(后台实际71-79道/台)',
  manager_user     bigint        DEFAULT NULL COMMENT '负责人(SSO用户ID)',
  machine_status   varchar(16)   NOT NULL DEFAULT '在线' COMMENT '机器状态:在线/故障/停用(撤点先生成退库单回仓库再停用,历史保留)',
  online_date      date          DEFAULT NULL COMMENT '上线日期(新机前4周用同类机器均值冷启动预测)',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_machine_device (tenant_id, device_id),
  UNIQUE KEY uk_machine_code (tenant_id, machine_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机器档案:后台设备ID唯一,是与售卖机后台对齐的锚点';

-- 5. 货道(planogram,库存最小单元)
CREATE TABLE yc_vend_slot (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  machine_id       bigint        NOT NULL COMMENT '机器ID',
  slot_no          varchar(16)   NOT NULL COMMENT '货道号(与后台货道号一致)',
  product_id       bigint        DEFAULT NULL COMMENT '当前绑定SKU(可从后台补货记录反推初始化)',
  capacity         decimal(12,3) NOT NULL DEFAULT 0 COMMENT '货道容量:机器层补货水位S的硬上限(S上限=该SKU货道数×单道容量)',
  current_qty      decimal(12,3) NOT NULL DEFAULT 0 COMMENT '当前数量(推算值,权威以后台缺货页/盘点为准,差异大→提示盘点)',
  slot_status      varchar(16)   NOT NULL DEFAULT '正常' COMMENT '货道状态:正常/停用/故障(吞货多→报修)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_slot (tenant_id, machine_id, slot_no),
  KEY idx_slot_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='货道:机器×货道号,绑SKU/容量/当前数量。售卖机系统区别于普通进销存的关键层';

-- 6. 人员角色映射(§11.2 轻角色,SSO身份来自园区主系统)
CREATE TABLE yc_vend_user_role (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  user_id          bigint        NOT NULL COMMENT '园区主系统SSO用户ID(不自建账号密码)',
  user_name        varchar(64)   DEFAULT NULL COMMENT '姓名快照(刘俊添/小邱/陈工)',
  role_code        varchar(16)   NOT NULL COMMENT '角色:BOSS老板/FINANCE财务/REPLENISH补货员/CLERK录单员。双签(录单人≠付款人)靠角色天然实现',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态:0=停用即断入口,历史op_log保留',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (tenant_id, user_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模块内轻角色映射:复用园区SSO身份,只管谁能干什么';

-- =====================================================================
-- 二、单据区 (M1 数据地基 · 单据头+明细通吃所有单据类型, 抄 jshERP)
-- =====================================================================

-- 7. 单据头
CREATE TABLE yc_vend_doc_head (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  doc_no           varchar(32)   NOT NULL COMMENT '单据号(如RK-20260801-001)',
  doc_type         varchar(16)   NOT NULL COMMENT '单据类型:采购入库/出库上架/退库/盘盈入库/盘亏出库/报损/红冲/成本调整/资金调整/期初。成本调整=P0-1单价错专用;资金调整=P1-7钱盘差异唯一出口;期初=上线向导',
  doc_status       varchar(16)   NOT NULL DEFAULT '草稿' COMMENT '状态机:草稿/预挂单/待确认/已确认/待结算/已结算/已完成/已红冲/已作废。只能按序流转。预挂单=手工转移单等待后台补货记录导入匹配(P0-4)',
  biz_date         date          NOT NULL COMMENT '业务日期',
  machine_id       bigint        DEFAULT NULL COMMENT '关联机器(出库上架的目标机/退库的来源机)',
  supplier_id      bigint        DEFAULT NULL COMMENT '关联供应商(采购入库/退库退供)',
  purchase_order_id bigint       DEFAULT NULL COMMENT '关联轻量订货单(收货单从订单带"应收"列,P0-5)',
  doc_source       varchar(16)   NOT NULL DEFAULT '手工' COMMENT '数据来源:手工/导入/系统生成。口径(P0-4):转移单唯一生产者=后台补货记录导入;手工转移单一律预挂单',
  import_batch_id  bigint        DEFAULT NULL COMMENT '来源导入批次(整批可回滚)',
  matched_doc_id   bigint        DEFAULT NULL COMMENT '预挂单被导入单冲抵时指向导入生成的转移单;超48h无后台记录才转正',
  red_flush_of     bigint        DEFAULT NULL COMMENT '红冲指向的原单据ID。红冲规则(P0-1):数量错→整单红冲(限无下游动作);红冲免负库存拦截但必过"影响清单"确认页;单据不许删只许红冲',
  neg_stock_exempt tinyint(1)    NOT NULL DEFAULT 0 COMMENT '负库存拦截豁免:1=豁免(仅导入生成的转移单与红冲单,P1-8),手工单不豁免;豁免放行后亮"待补录采购"红灯',
  total_qty        decimal(12,3) NOT NULL DEFAULT 0 COMMENT '总数量',
  total_amount     decimal(12,2) NOT NULL DEFAULT 0 COMMENT '总金额',
  due_date         date          DEFAULT NULL COMMENT '应付到期日(供应商账期推算,逾期黄灯)',
  handler_user     bigint        DEFAULT NULL COMMENT '经手人(收货/补货执行人)',
  confirm_by       bigint        DEFAULT NULL COMMENT '一级确认人(经手人确认,§9.1确认留名)',
  confirm_at       datetime      DEFAULT NULL COMMENT '一级确认时间',
  confirm2_by      bigint        DEFAULT NULL COMMENT '二级确认人(老板复核,与一级不同人,双签牵制)',
  confirm2_at      datetime      DEFAULT NULL COMMENT '二级确认时间',
  book_period      char(7)       DEFAULT NULL COMMENT '入账月YYYY-MM(锁账后补录的单据入当月,旧报表永不重算,P0-2)',
  remark           varchar(500)  DEFAULT NULL COMMENT '备注(锁账期红冲需老板角色+强制备注)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除(已确认单据禁物理/逻辑删,只红冲)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_no (tenant_id, doc_no),
  KEY idx_doc_type_date (tenant_id, doc_type, biz_date),
  KEY idx_doc_machine (machine_id),
  KEY idx_doc_supplier (supplier_id),
  KEY idx_doc_red (red_flush_of),
  KEY idx_doc_import (import_batch_id),
  KEY idx_doc_status (tenant_id, doc_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据头:一套状态机通吃9+1种单据。口径:单据即台账,不许删只红冲;出库上架=仓库→机器转移单';

-- 8. 单据明细
CREATE TABLE yc_vend_doc_item (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  doc_id           bigint        NOT NULL COMMENT '单据头ID',
  product_id       bigint        NOT NULL COMMENT '商品SKU',
  slot_no          varchar(16)   DEFAULT NULL COMMENT '货道号(出库上架/退库时精确到货道,来自后台补货记录;负数补货=逆向转移)',
  box_qty          decimal(12,3) DEFAULT NULL COMMENT '整箱数(按product.box_spec换算)',
  qty              decimal(12,3) NOT NULL DEFAULT 0 COMMENT '数量(基本单位;逆向单为正数,方向由doc_type决定)',
  expect_qty       decimal(12,3) DEFAULT NULL COMMENT '应收数量(从purchase_order_item带入,收货差异=qty-expect_qty自动标注"少2瓶按实收")',
  unit_price       decimal(12,4) NOT NULL DEFAULT 0 COMMENT '单价(采购价随单记录,自动形成价格历史;成本调整单存"新单价")',
  amount           decimal(12,2) NOT NULL DEFAULT 0 COMMENT '金额=qty×unit_price',
  batch_no         varchar(32)   DEFAULT NULL COMMENT '批次号(M2 FEFO用)',
  expire_date      date          DEFAULT NULL COMMENT '到期日(FEFO先过期先出;临期预警)',
  po_item_id       bigint        DEFAULT NULL COMMENT '关联订货单明细(回写已收量,在途=Σ(订购-已收))',
  remark           varchar(255)  DEFAULT NULL COMMENT '行备注(盘亏原因/成本调整拆分说明等)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_ditem_doc (doc_id),
  KEY idx_ditem_product (product_id),
  KEY idx_ditem_po (po_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据明细:数量/单价/批次/金额;应收列支撑收货差异';

-- 9. 轻量采购订货单(P0-5,草稿级不进账)
CREATE TABLE yc_vend_purchase_order (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  po_no            varchar(32)   NOT NULL COMMENT '订货单号',
  supplier_id      bigint        NOT NULL COMMENT '供应商',
  expect_date      date          DEFAULT NULL COMMENT '预计到货日(超期未到→首页黄灯)',
  po_status        varchar(16)   NOT NULL DEFAULT '草稿' COMMENT '状态:草稿/已下单/部分到货/已完成/已关闭',
  total_amount     decimal(12,2) NOT NULL DEFAULT 0 COMMENT '预计总金额',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_po_no (tenant_id, po_no),
  KEY idx_po_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轻量订货单:草稿级不进账,是"在途库存"的唯一数据生产者(修穿行场景2断点)';

-- 10. 订货单明细
CREATE TABLE yc_vend_purchase_order_item (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  po_id            bigint        NOT NULL COMMENT '订货单ID',
  product_id       bigint        NOT NULL COMMENT '商品SKU',
  qty_ordered      decimal(12,3) NOT NULL DEFAULT 0 COMMENT '订购数量',
  qty_received     decimal(12,3) NOT NULL DEFAULT 0 COMMENT '已收数量(收货入库单确认时回写)。在途=Σ(qty_ordered-qty_received),补货公式"当前库存(机内+在途)"从这里取数',
  unit_price       decimal(12,4) NOT NULL DEFAULT 0 COMMENT '预计单价',
  amount           decimal(12,2) NOT NULL DEFAULT 0 COMMENT '预计金额',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_poi_po (po_id),
  KEY idx_poi_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订货单明细:订购/已收两列,差值=在途';

-- =====================================================================
-- 三、销售与导入区 (M1 数据地基 · 导入中心是全系统数据入口)
-- =====================================================================

-- 11. 销售记录
CREATE TABLE yc_vend_sale_record (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  order_no         varchar(64)   NOT NULL COMMENT '订单号:导入去重键,重复导入零副作用;线下补录自动生成OFFLINE-前缀单号',
  machine_id       bigint        DEFAULT NULL COMMENT '机器(按device_id映射)',
  device_id        varchar(64)   DEFAULT NULL COMMENT '后台设备ID原值(留痕)',
  slot_no          varchar(16)   DEFAULT NULL COMMENT '货道号',
  alias_code_raw   varchar(64)   DEFAULT NULL COMMENT '后台商品编号原值(别名匹配主键之一)',
  alias_name_raw   varchar(128)  DEFAULT NULL COMMENT '后台销售商品名原值(归集前留痕,仅展示)',
  product_id       bigint        DEFAULT NULL COMMENT '归集后SKU(经sku_alias映射;NULL=待绑定,进alias_pending队列)',
  qty              decimal(12,3) NOT NULL DEFAULT 1 COMMENT '出货数量',
  amount_received  decimal(12,2) NOT NULL DEFAULT 0 COMMENT '实收金额:毛利口径唯一收入依据(§13.1毛利=实收-移动加权成本);退款为负',
  unit_price       decimal(12,4) DEFAULT NULL COMMENT '成交单价(导入侦测:≠product.ref_price→确认更新档案+写price_log,P2-9)',
  pay_method       varchar(16)   DEFAULT NULL COMMENT '支付方式:微信/支付宝/线下/兑换',
  order_type       varchar(16)   NOT NULL DEFAULT '正常' COMMENT '订单类型三口径(P0-3,§13.2-3):①结算对账口径=仅"正常"(退款为负)②补货日均销量口径=正常+兑换(真消耗库存),不含测试③毛利口径:兑换收入按0、成本由补贴到账对冲。枚举:正常/兑换/退款/测试/线下补录。线下补录不入待结算虚账',
  biz_time         datetime      NOT NULL COMMENT '业务时间戳(出货时间):机器库存增量推算按此排序,不按导入顺序(修穿行场景11)',
  biz_period       char(7)       NOT NULL COMMENT '业务归属月YYYY-MM(按biz_time)',
  book_period      char(7)       NOT NULL COMMENT '入账月YYYY-MM(P0-2锁账×补导):锁账后补导的记录book_period=当月,旧报表永不重算,当月利润表"上期调整"行承接',
  cost_amount      decimal(12,2) DEFAULT NULL COMMENT '销售成本快照(移动加权;无采购史SKU为NULL→毛利显示"—",禁用0)',
  settlement_id    bigint        DEFAULT NULL COMMENT '平台结算单回填(NULL=在途货款,计入平台待结算虚账;仅order_type=正常/退款参与)',
  import_batch_id  bigint        DEFAULT NULL COMMENT '导入批次(整批可回滚)',
  offline_flag     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '线下复合单标记(P2-13):1=同时生成cash_flow平台外收入+机内库存差异豁免',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sale_order (tenant_id, order_no, order_type),  -- 主窗口裁决:防后台退款与原单同order_no另起一行撞键;正常单重复导入仍被拦
  KEY idx_sale_machine_time (machine_id, biz_time),
  KEY idx_sale_product_time (product_id, biz_time),
  KEY idx_sale_period (tenant_id, biz_period),
  KEY idx_sale_book (tenant_id, book_period),
  KEY idx_sale_settlement (settlement_id),
  KEY idx_sale_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售记录:后台出货明细导入+手工补录。硬规则:后台已有出货禁止再手工录出库(保存时按机器+SKU+日期碰撞检查)';

-- 12. 导入批次
CREATE TABLE yc_vend_import_batch (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  batch_no         varchar(32)   NOT NULL COMMENT '批次号',
  file_name        varchar(255)  NOT NULL COMMENT '原始文件名',
  file_type        varchar(16)   NOT NULL COMMENT '导入类型:出货明细/系统补货记录/商品列表/期初数据。期初通道带编码冲突清洗步骤:老账一码多品(SP009/010/011/012/046/069)强制拆分后才放行',
  archive_path     varchar(500)  DEFAULT NULL COMMENT '原始文件归档路径(留档可追溯)',
  period_range     varchar(32)   DEFAULT NULL COMMENT '数据覆盖区间(后台出货明细查询限当月+上月,每月至少导一次)',
  row_total        int           NOT NULL DEFAULT 0 COMMENT '总行数',
  row_ok           int           NOT NULL DEFAULT 0 COMMENT '成功行数',
  row_fail         int           NOT NULL DEFAULT 0 COMMENT '失败行数',
  row_dup          int           NOT NULL DEFAULT 0 COMMENT '重复跳过行数(按去重键)',
  batch_status     varchar(16)   NOT NULL DEFAULT '已导入' COMMENT '状态:处理中/已导入/已回滚。整批回滚=撤销本批次生成的全部记录与单据',
  column_map_json  text          COMMENT '列映射(厂家改模板时LLM猜新映射,人工确认后保存,接入点#9)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_no (tenant_id, batch_no),
  KEY idx_batch_type (tenant_id, file_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次:导入中心是全系统数据入口;三通道=出货明细(销售)/系统补货记录(转移单)/商品列表(别名)';

-- 13. 导入行级错误
CREATE TABLE yc_vend_import_error (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  batch_id         bigint        NOT NULL COMMENT '导入批次ID',
  row_no           int           NOT NULL COMMENT '原文件行号',
  raw_content      text          COMMENT '原始行内容',
  error_type       varchar(32)   DEFAULT NULL COMMENT '错误类型:别名未绑定/格式错误/机器不存在/重复/编码冲突(一码多品,期初清洗未拆分)',
  error_msg        varchar(500)  DEFAULT NULL COMMENT '错误说明',
  resolve_status   varchar(16)   NOT NULL DEFAULT '待处理' COMMENT '处理状态:待处理/已重导/已忽略',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_ierr_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入行级错误:失败行留痕,支持修复后重导';

-- 14. 别名待绑定队列
CREATE TABLE yc_vend_alias_pending (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  alias_code       varchar(64)   NOT NULL DEFAULT '' COMMENT '未识别的后台商品编号(主匹配键)',
  alias_barcode    varchar(64)   NOT NULL DEFAULT '' COMMENT '条码(主匹配键)',
  alias_name       varchar(128)  NOT NULL COMMENT '后台商品名(展示用,不参与唯一键)',
  first_batch_id   bigint        DEFAULT NULL COMMENT '首次出现的导入批次',
  hit_count        int           NOT NULL DEFAULT 1 COMMENT '累计出现次数',
  suggest_product_id bigint      DEFAULT NULL COMMENT 'AI建议SKU(向量召回+LLM复核,接入点#1)',
  ai_confidence    decimal(8,4)  DEFAULT NULL COMMENT 'AI置信度',
  llm_call_id      bigint        DEFAULT NULL COMMENT 'AI调用记录',
  pending_status   varchar(16)   NOT NULL DEFAULT '待绑定' COMMENT '状态:待绑定/已绑定/已建新品/忽略。确认绑定→写入sku_alias并回补关联sale_record',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pending_alias (tenant_id, alias_code, alias_barcode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='别名待绑定队列:导入遇新编号/条码进队列,绑一次终身生效';

-- =====================================================================
-- 四、库存区 (M1 数据地基)
-- =====================================================================

-- 15. 仓库库存流水
CREATE TABLE yc_vend_stock_ledger (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  product_id       bigint        NOT NULL COMMENT '商品SKU',
  location_type    varchar(8)    NOT NULL DEFAULT '仓库' COMMENT '账本:仓库/机器(两级库存;机器侧权威在后台,本表为经营侧推算账)',
  machine_id       bigint        DEFAULT NULL COMMENT '机器ID(location_type=机器时必填)',
  doc_id           bigint        NOT NULL COMMENT '来源单据头:库存流水唯一由单据产生,禁止直改库存',
  doc_item_id      bigint        DEFAULT NULL COMMENT '来源单据明细',
  change_qty       decimal(12,3) NOT NULL COMMENT '变动数量(+入/-出)',
  balance_qty      decimal(12,3) NOT NULL COMMENT '变动后结存(负库存默认拦截;导入转移单/红冲豁免并亮红灯)',
  unit_cost        decimal(12,4) DEFAULT NULL COMMENT '移动加权单位成本快照(成本调整单在此落新成本;无采购史=NULL)',
  amount           decimal(12,2) DEFAULT NULL COMMENT '变动金额(成本口径)',
  biz_time         datetime      NOT NULL COMMENT '业务时间戳(乱序导入按此重算,不按写入顺序)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_ledger_product (tenant_id, product_id, location_type, machine_id, biz_time),
  KEY idx_ledger_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水:唯一由单据产生的两级库存账。期初/期末/进销存汇总全部由流水推算,不落静态余额表';

-- 16. 机器库存快照
CREATE TABLE yc_vend_machine_stock_snapshot (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  machine_id       bigint        NOT NULL COMMENT '机器ID',
  product_id       bigint        NOT NULL COMMENT '商品SKU',
  slot_no          varchar(16)   DEFAULT NULL COMMENT '货道号(可到货道级)',
  snapshot_time    datetime      NOT NULL COMMENT '快照业务时间',
  qty              decimal(12,3) NOT NULL DEFAULT 0 COMMENT '快照数量',
  snapshot_source  varchar(16)   NOT NULL COMMENT '来源:后台缺货页/盘点/补货记录。口径(P2-12):机器实时库存=最近快照+快照之后按业务时间戳的增量(销售-补货),与导入顺序无关',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_snap_machine (tenant_id, machine_id, product_id, snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机器库存快照:锚点+增量推算法的锚点表;与后台"现有库存数"对账,差异大→提示盘点';

-- =====================================================================
-- 五、补货区 (M2 补货闭环)
-- =====================================================================

-- 17. 补货参数
CREATE TABLE yc_vend_replenish_config (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  scope_type       varchar(16)   NOT NULL DEFAULT '全局' COMMENT '作用域:全局/SKU/机器/机器×SKU(细覆盖粗)',
  product_id       bigint        NOT NULL DEFAULT 0 COMMENT 'SKU(0=不限)',
  machine_id       bigint        NOT NULL DEFAULT 0 COMMENT '机器(0=不限)',
  cycle_days       int           NOT NULL DEFAULT 15 COMMENT '补货周期R(天):用户自定义15/7/30',
  service_level    decimal(8,4)  NOT NULL DEFAULT 0.9500 COMMENT '服务水平:爆款0.98/长尾0.90/新品试销0.85(Z系数由此查表)',
  lead_time_days   decimal(12,3) NOT NULL DEFAULT 1 COMMENT '提前期L(天):仓库→机器0-1天;供应商采购2-7天',
  expire_warn_days int           DEFAULT NULL COMMENT '临期预警天数(参数与阈值Tab)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人(每次改动写op_log:旧值→新值→改动人)',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rep_cfg (tenant_id, machine_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补货参数:(R,S)模型输入,全局+按SKU/机器覆盖;参数修改留痕,下周期对比(补货PDCA)';

-- 18. 补货建议
CREATE TABLE yc_vend_replenish_plan (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  plan_date        date          NOT NULL COMMENT '建议生成日',
  plan_type        varchar(16)   NOT NULL DEFAULT '机器补货' COMMENT '类型:机器补货(仓库→机器)/采购建议(供应商→仓库)',
  machine_id       bigint        DEFAULT NULL COMMENT '机器(采购建议为NULL)',
  product_id       bigint        NOT NULL COMMENT '商品SKU',
  current_qty      decimal(12,3) NOT NULL DEFAULT 0 COMMENT '当前库存(机内+在途,在途取自purchase_order)',
  avg_daily        decimal(12,3) DEFAULT NULL COMMENT '日均销量d̄(近4-8周,口径=正常+兑换,不含测试)',
  sigma_daily      decimal(12,3) DEFAULT NULL COMMENT '日销量标准差σd',
  target_level_s   decimal(12,3) DEFAULT NULL COMMENT '补到水位S=d̄×(R+L)+SS;机器层受货道容量硬约束,超限→提示加货道',
  safety_stock     decimal(12,3) DEFAULT NULL COMMENT '安全库存SS=Z×σd×√(R+L)',
  suggest_qty      decimal(12,3) NOT NULL DEFAULT 0 COMMENT '建议补货量Q=S-当前库存',
  box_round_qty    decimal(12,3) DEFAULT NULL COMMENT '整箱取整后数量(向上取整到box_spec)',
  plan_status      varchar(16)   NOT NULL DEFAULT '建议' COMMENT '状态:建议/已采纳/已忽略/已生成配货单/已执行',
  formula_json     text          COMMENT '公式与全部输入值快照(每个数字可点开看到公式,🔬这个数怎么算出来的)',
  ai_explain       text          COMMENT 'LLM人话解释(规则出数字,LLM出解释;接入点#3)',
  llm_call_id      bigint        DEFAULT NULL COMMENT 'AI调用记录(透明四件套)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_plan_date (tenant_id, plan_date),
  KEY idx_plan_machine (machine_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补货建议:(R,S)模型输出,机器补货+采购建议两类;清仓中商品不进建议';

-- 19. Pre-kit 配货单(P2-12 落表)
CREATE TABLE yc_vend_prekit_ticket (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  ticket_no        varchar(32)   NOT NULL COMMENT '配货单号',
  machine_id       bigint        NOT NULL COMMENT '目标机器(一机一箱)',
  plan_date        date          NOT NULL COMMENT '配货日期',
  ticket_status    varchar(16)   NOT NULL DEFAULT '已生成' COMMENT '状态:已生成/已执行/已核销/有差异。核销规则(修穿行场景1):次日按机器+日期窗口匹配后台导入生成的转移单,差量=带回',
  verify_doc_id    bigint        DEFAULT NULL COMMENT '核销匹配的转移单(doc_head)',
  takeback_rate    decimal(8,4)  DEFAULT NULL COMMENT '带回率=Σ带回/Σ带出:补货PDCA核心指标的唯一数据生产者',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_prekit_no (tenant_id, ticket_no),
  KEY idx_prekit_machine (machine_id, plan_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Pre-kit配货单:按机器分组拣货装箱;核销差量自动算带回率';

-- 20. 配货单明细
CREATE TABLE yc_vend_prekit_ticket_item (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  ticket_id        bigint        NOT NULL COMMENT '配货单ID',
  product_id       bigint        NOT NULL COMMENT '商品SKU',
  qty_planned      decimal(12,3) NOT NULL DEFAULT 0 COMMENT '计划带出数量',
  qty_loaded       decimal(12,3) DEFAULT NULL COMMENT '实际上架数量(核销时从匹配转移单回填)',
  qty_takeback     decimal(12,3) DEFAULT NULL COMMENT '带回数量=带出-上架',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_pki_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配货单明细:计划/实上架/带回三列';

-- =====================================================================
-- 六、钱账区 (M3 钱账 · 单据驱动,流水只有一张表)
-- =====================================================================

-- 21. 资金账户
CREATE TABLE yc_vend_account (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  account_name     varchar(64)   NOT NULL COMMENT '账户名',
  account_type     varchar(16)   NOT NULL COMMENT '类型:微信/支付宝/银行卡/现金/平台待结算/老板垫付',
  is_virtual       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '虚拟账户:1=平台待结算/老板垫付(不参与钱盘实数核对)',
  opening_balance  decimal(12,2) NOT NULL DEFAULT 0 COMMENT '期初余额:只能设一次,后续改动一律走资金调整单留痕',
  opening_set_at   datetime      DEFAULT NULL COMMENT '期初设定时间(非空即锁定)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_name (tenant_id, account_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金账户:真实+虚拟。余额=期初+Σ流水,实时推算不落静态余额';

-- 22. 资金流水(全系统唯一钱账)
CREATE TABLE yc_vend_cash_flow (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  flow_no          varchar(32)   NOT NULL COMMENT '流水号',
  account_id       bigint        NOT NULL COMMENT '账户',
  direction        varchar(4)    NOT NULL COMMENT '方向:收/支',
  amount           decimal(12,2) NOT NULL COMMENT '金额(恒正,方向看direction)',
  category         varchar(32)   NOT NULL COMMENT '业务类别:货款结算/供应商付款/电费/维修/杂支/设备购置/索赔到账/线下收入/兑换收款/资金调整等',
  pl_line          varchar(32)   NOT NULL COMMENT '利润表行映射(P0-6,§13.1):销售收入/平台手续费/杂费/损耗/成本调整/其他收入-赔付/其他收入-平台外/其他收入-补贴/不入利润表-往来/不入利润表-资金调整。规则:每类流水在利润表有且只有一个去处',
  ref_doc_type     varchar(24)   DEFAULT NULL COMMENT '来源单据类型:付款单/平台结算单/支出单/索赔单/资金调整单/线下复合单',
  ref_doc_id       bigint        DEFAULT NULL COMMENT '来源单据ID。铁律(§9.1):钱不允许直接记一笔,任何流水必须由单据生成',
  biz_time         datetime      NOT NULL COMMENT '业务时间',
  book_period      char(7)       NOT NULL COMMENT '入账月(锁账口径同sale_record)',
  remark           varchar(255)  DEFAULT NULL COMMENT '摘要',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除(流水不许删,错账走资金调整/红冲)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_flow_no (tenant_id, flow_no),
  KEY idx_flow_account (account_id, biz_time),
  KEY idx_flow_pl (tenant_id, pl_line, book_period),
  KEY idx_flow_ref (ref_doc_type, ref_doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金流水:全系统唯一钱账,每笔进出必关联单据;类别与利润表行一一映射';

-- 23. 付款单
CREATE TABLE yc_vend_payment (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  pay_no           varchar(32)   NOT NULL COMMENT '付款单号',
  supplier_id      bigint        NOT NULL COMMENT '付给谁',
  account_id       bigint        NOT NULL COMMENT '从哪个账户',
  amount           decimal(12,2) NOT NULL COMMENT '实付金额(如1911.37=2254-342.63抵扣)',
  deduction_amount decimal(12,2) NOT NULL DEFAULT 0 COMMENT '兑换/补贴抵扣额(自动从deduction待抵扣带入,对应"厂家已结账217.3"类业务)',
  settle_bill_id   bigint        DEFAULT NULL COMMENT '关联应付结算单(可选,不强制逐单核销;预付=供应商余额为负)',
  pay_status       varchar(16)   NOT NULL DEFAULT '待付款' COMMENT '状态:待付款/已付款/结算完成/差异挂起(凭证金额≠单金额→红灯挂起)',
  confirm_by       bigint        DEFAULT NULL COMMENT '付款确认人(须≠录单人,双签)',
  confirm_at       datetime      DEFAULT NULL COMMENT '确认时间',
  pay_time         datetime      DEFAULT NULL COMMENT '付款时间',
  book_period      char(7)       DEFAULT NULL COMMENT '入账月',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_no (tenant_id, pay_no),
  KEY idx_pay_supplier (supplier_id),
  KEY idx_pay_bill (settle_bill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款单:三要素付给谁/从哪个账户/多少钱;无转账截图凭证不能进"已付款"';

-- 24. 结算单(应付/应收通用)
CREATE TABLE yc_vend_settle_bill (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  bill_no          varchar(32)   NOT NULL COMMENT '结算单号',
  bill_type        varchar(8)    NOT NULL DEFAULT '应付' COMMENT '类型:应付/应收',
  direction        varchar(8)    NOT NULL DEFAULT '正常' COMMENT '方向(P0-1红冲连锁):正常/红字。已付款的采购单发现错→生成应付红字冲抵下一单,不动已完成付款',
  supplier_id      bigint        DEFAULT NULL COMMENT '供应商',
  source_doc_id    bigint        DEFAULT NULL COMMENT '来源单据链(入库单确认后自动生成)',
  amount_due       decimal(12,2) NOT NULL DEFAULT 0 COMMENT '应结金额(=实收入库金额)',
  deduction_amount decimal(12,2) NOT NULL DEFAULT 0 COMMENT '抵扣明细合计(仅允许带入同供应商待抵扣,P2-11)',
  amount_actual    decimal(12,2) NOT NULL DEFAULT 0 COMMENT '实结金额=应结-抵扣',
  bill_status      varchar(16)   NOT NULL DEFAULT '待确认' COMMENT '状态:待确认/待付款/已付款/已完成/差异挂起',
  lock_diff_note   varchar(255)  DEFAULT NULL COMMENT '锁后新增差异提示(P0-2):已核销结算单遇锁账后补导只展示提示,不改状态',
  due_date         date          DEFAULT NULL COMMENT '到期日',
  book_period      char(7)       DEFAULT NULL COMMENT '入账月',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bill_no (tenant_id, bill_no),
  KEY idx_bill_supplier (supplier_id),
  KEY idx_bill_source (source_doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算单:业财一体链条中枢;direction=红字支撑红冲连锁的应付侧';

-- 25. 平台结算单
CREATE TABLE yc_vend_settlement (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  stmt_no          varchar(32)   NOT NULL COMMENT '平台结算单号',
  period_start     date          NOT NULL COMMENT '结算区间起',
  period_end       date          NOT NULL COMMENT '结算区间止',
  platform_amount  decimal(12,2) NOT NULL DEFAULT 0 COMMENT '平台账单销售额',
  fee_amount       decimal(12,2) NOT NULL DEFAULT 0 COMMENT '平台手续费(报表单列"平台这个月吃掉了我多少钱")',
  actual_amount    decimal(12,2) NOT NULL DEFAULT 0 COMMENT '实际到账',
  system_amount    decimal(12,2) NOT NULL DEFAULT 0 COMMENT '系统销售额快照(口径=仅order_type正常,退款为负,P0-3)',
  diff_sales       decimal(12,2) NOT NULL DEFAULT 0 COMMENT '差异①=系统销售额-平台账单额(差=漏单/吞货),超阈值红灯',
  diff_arrival     decimal(12,2) NOT NULL DEFAULT 0 COMMENT '差异②=预计到账-实际到账(差=费率变化/额外扣款)',
  account_id       bigint        DEFAULT NULL COMMENT '入账账户',
  stl_status       varchar(16)   NOT NULL DEFAULT '预生成' COMMENT '状态:预生成/待核对/已核销/差异挂起。核销=写流水+清平台待结算+回填sale_record.settlement_id',
  book_period      char(7)       DEFAULT NULL COMMENT '入账月',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stmt_no (tenant_id, stmt_no),
  KEY idx_stmt_period (period_start, period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台结算单:系统按周期预生成,到账登记双差异核对;无平台账单+到账截图凭证不能核销';

-- 26. 抵扣确认单(兑换/补贴)
CREATE TABLE yc_vend_deduction (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  ded_no           varchar(32)   NOT NULL COMMENT '抵扣单号',
  supplier_id      bigint        NOT NULL COMMENT '供应商(P2-11必填):结算单只允许带入同供应商待抵扣,防串户(修穿行场景13)',
  ded_source       varchar(16)   NOT NULL DEFAULT '兑换' COMMENT '来源:兑换/厂家补贴',
  amount           decimal(12,2) NOT NULL COMMENT '抵扣金额(如厂家已结账351.63)',
  ded_status       varchar(16)   NOT NULL DEFAULT '待抵扣' COMMENT '状态:待抵扣/已用于结算单X/作废',
  used_settle_bill_id bigint     DEFAULT NULL COMMENT '被使用的结算单',
  period_desc      varchar(64)   DEFAULT NULL COMMENT '对应兑换活动区间说明',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ded_no (tenant_id, ded_no),
  KEY idx_ded_supplier (supplier_id, ded_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抵扣确认单:兑换/补贴形成待抵扣额,进下张应付结算单;厂家确认凭证必传;兑换销售的成本由此对冲(三口径之毛利口径)';

-- 27. 索赔单
CREATE TABLE yc_vend_claim (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  claim_no         varchar(32)   NOT NULL COMMENT '索赔单号',
  source_type      varchar(16)   NOT NULL DEFAULT '盘亏' COMMENT '来源:盘亏归因(吞货)/其他',
  source_id        bigint        DEFAULT NULL COMMENT '来源盘点单/盘亏单ID',
  claim_target     varchar(16)   NOT NULL COMMENT '索赔对象:厂家/平台',
  amount           decimal(12,2) NOT NULL COMMENT '索赔金额',
  claim_status     varchar(16)   NOT NULL DEFAULT '申请中' COMMENT '状态:申请中/已到账/放弃。口径(P0-6,§13.1):申请中计入资产快照"索赔应收";到账→写cash_flow(其他收入-赔付)并关闭;净损耗=损耗-已获赔',
  received_amount  decimal(12,2) DEFAULT NULL COMMENT '实际到账金额',
  received_time    datetime      DEFAULT NULL COMMENT '到账时间',
  cash_flow_id     bigint        DEFAULT NULL COMMENT '到账生成的流水',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_claim_no (tenant_id, claim_no),
  KEY idx_claim_status (tenant_id, claim_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='索赔单:赔付凭证必传;修穿行场景6(索赔应收进资产公式、赔付进利润表其他收入)';

-- 28. 支出单(杂费/设备)
CREATE TABLE yc_vend_expense (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  exp_no           varchar(32)   NOT NULL COMMENT '支出单号',
  category         varchar(32)   NOT NULL COMMENT '类别:电费/维修/杂支/设备购置',
  amount           decimal(12,2) NOT NULL COMMENT '金额',
  account_id       bigint        NOT NULL COMMENT '支出账户',
  is_equipment     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否设备:1=同步写入equipment台账',
  equipment_id     bigint        DEFAULT NULL COMMENT '关联设备台账',
  exp_status       varchar(16)   NOT NULL DEFAULT '待确认' COMMENT '状态:待确认/已完成(老板确认+付款截图/发票凭证)',
  biz_date         date          NOT NULL COMMENT '支出日期',
  book_period      char(7)       DEFAULT NULL COMMENT '入账月',
  remark           varchar(255)  DEFAULT NULL COMMENT '摘要(如"机器坏了直接转13.2"归线下收入,不在本表)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_exp_no (tenant_id, exp_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支出单:杂费与设备购置;利润表"杂费"行来源';

-- =====================================================================
-- 七、盘点与资产区 (M3 钱账)
-- =====================================================================

-- 29. 盘点单
CREATE TABLE yc_vend_stocktake (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  st_no            varchar(32)   NOT NULL COMMENT '盘点单号',
  scope_type       varchar(8)    NOT NULL COMMENT '范围:仓库/机器',
  machine_id       bigint        DEFAULT NULL COMMENT '机器(scope=机器时)',
  snapshot_time    datetime      NOT NULL COMMENT '账面快照时间(系统自动快照账面数)',
  st_status        varchar(16)   NOT NULL DEFAULT '进行中' COMMENT '状态:进行中/待确认/已完成。单笔差异>¥50标红需老板确认(阈值可配)',
  gain_doc_id      bigint        DEFAULT NULL COMMENT '生成的盘盈入库单',
  loss_doc_id      bigint        DEFAULT NULL COMMENT '生成的盘亏出库单(确认时强制走五步向导:先查账→归因→处理/索赔→改进→下轮验证)',
  source_task      varchar(32)   DEFAULT NULL COMMENT '来源:月度SOP任务包/补货顺手盘/手动',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_st_no (tenant_id, st_no),
  KEY idx_st_machine (machine_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点单:手机优先,只录有差异的;确认自动生成盘盈/盘亏单(复用单据通道)';

-- 30. 盘点明细
CREATE TABLE yc_vend_stocktake_item (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  stocktake_id     bigint        NOT NULL COMMENT '盘点单ID',
  product_id       bigint        NOT NULL COMMENT '商品SKU',
  slot_no          varchar(16)   DEFAULT NULL COMMENT '货道号(机器盘点到货道级)',
  book_qty         decimal(12,3) NOT NULL DEFAULT 0 COMMENT '账面快照数',
  actual_qty       decimal(12,3) NOT NULL DEFAULT 0 COMMENT '实盘数',
  diff_qty         decimal(12,3) NOT NULL DEFAULT 0 COMMENT '差异=实盘-账面',
  diff_amount      decimal(12,2) DEFAULT NULL COMMENT '差异金额(按移动加权成本)',
  diff_reason      varchar(16)   DEFAULT NULL COMMENT '差异原因枚举(差异行必选):吞货掉货/过期报损/录入错误/被盗/盘点错误/原因不明。第1步先查账(漏导入/录错)由系统自动做',
  claim_id         bigint        DEFAULT NULL COMMENT '吞货可索赔→关联索赔单',
  offline_exempt   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '线下销售差异豁免标记(P2-13)',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_sti_st (stocktake_id),
  KEY idx_sti_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点明细:账面/实盘/差异/原因;损耗报表按原因统计,反哺补货参数';

-- 31. 设备台账
CREATE TABLE yc_vend_equipment (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  equip_name       varchar(64)   NOT NULL COMMENT '设备名称',
  machine_id       bigint        DEFAULT NULL COMMENT '关联机器(售卖机本体)',
  buy_price        decimal(12,2) NOT NULL DEFAULT 0 COMMENT '购入价',
  buy_date         date          DEFAULT NULL COMMENT '购入日期',
  residual_value   decimal(12,2) DEFAULT NULL COMMENT '折余价值(展示用:总投入/回本进度;不进钱账流水,单台<5000一次性费用化)',
  equip_status     varchar(16)   NOT NULL DEFAULT '在用' COMMENT '状态:在用/报废/出售',
  expense_id       bigint        DEFAULT NULL COMMENT '来源支出单',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备台账:回本进度展示,不进利润表与流水';

-- 32. 资产快照(月度)
CREATE TABLE yc_vend_asset_snapshot (
  id                 bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id          varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  period             char(7)       NOT NULL COMMENT '快照月YYYY-MM',
  inventory_amount   decimal(12,2) NOT NULL DEFAULT 0 COMMENT '库存资产(成本价:仓库+机器两级)',
  platform_pending   decimal(12,2) NOT NULL DEFAULT 0 COMMENT '平台待结算(虚拟账户余额)',
  cash_total         decimal(12,2) NOT NULL DEFAULT 0 COMMENT '各真实账户现金合计',
  claim_receivable   decimal(12,2) NOT NULL DEFAULT 0 COMMENT '索赔应收(claim申请中,P0-6新增项)',
  payable_total      decimal(12,2) NOT NULL DEFAULT 0 COMMENT '应付供应商合计',
  net_asset          decimal(12,2) NOT NULL DEFAULT 0 COMMENT '净流动资产=库存+待结算+现金+索赔应收-应付(§13.1公式,效力最高)',
  detail_json        text          COMMENT '各项下钻明细快照',
  create_user        bigint        DEFAULT NULL COMMENT '创建人',
  create_dept        bigint        DEFAULT NULL COMMENT '创建部门',
  create_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user        bigint        DEFAULT NULL COMMENT '更新人',
  update_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status             tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted         tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_period (tenant_id, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产快照:月度自动,净家底趋势数据源;归档后永不重算(锁账口径)';

-- 33. 锁账记录
CREATE TABLE yc_vend_period_lock (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  period           char(7)       NOT NULL COMMENT '锁定月YYYY-MM',
  locked_at        datetime      NOT NULL COMMENT '锁账时间(默认每月5日出完报表后)',
  locked_by        bigint        NOT NULL COMMENT '锁账人(老板/财务)',
  lock_note        varchar(255)  DEFAULT NULL COMMENT '说明。规则(P0-2):锁定期之前单据不许改;红冲需老板角色+强制备注;锁后补导→book_period=当月,旧报表永不重算,"上期调整"行承接',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lock_period (tenant_id, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='月结锁账记录:锁账只管改单不管补导入,补导走book_period口径';

-- =====================================================================
-- 八、PDCA / 留痕 / AI 区 (M4 BI-PDCA-AI)
-- =====================================================================

-- 34. 改进任务(PDCA A 环节唯一落点)
CREATE TABLE yc_vend_action_item (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  source_scene     varchar(16)   NOT NULL COMMENT '来源环节:补货/采购/选品/定价/钱账/盘点',
  problem_desc     varchar(500)  NOT NULL COMMENT '问题描述(如"过期报损集中在面包")',
  measure          varchar(500)  NOT NULL COMMENT '改进措施(如"机内上限24→12")',
  owner_user       bigint        DEFAULT NULL COMMENT '负责人',
  verify_metric    varchar(255)  NOT NULL COMMENT '验证指标(如"过期报损<¥10/月"):到期自动回查',
  verify_date      date          NOT NULL COMMENT '验证日期(通常=下次月盘),到期驾驶舱提醒',
  item_status      varchar(16)   NOT NULL DEFAULT '进行中' COMMENT '状态:进行中/验证通过/未见效升级/已关闭。下轮验证:该原因指标环比↓→关闭;没↓→升级处理',
  source_ref_type  varchar(32)   DEFAULT NULL COMMENT '来源引用类型(盘点单/月度报告/五步向导)',
  source_ref_id    bigint        DEFAULT NULL COMMENT '来源引用ID',
  ai_draft         tinyint(1)    NOT NULL DEFAULT 0 COMMENT 'LLM起草标记(接入点#7:AI建议→采纳→验证闭环)',
  llm_call_id      bigint        DEFAULT NULL COMMENT 'AI调用记录',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_action_status (tenant_id, item_status, verify_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='改进任务:所有环节的A统一落此表,带验证指标+期限,改进不再说过就忘';

-- 35. 固定任务日历(任务日历页·固定任务引擎)
CREATE TABLE yc_vend_routine_task (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  task_key         varchar(32)   NOT NULL COMMENT '任务键:月度盘点SOP/平台结算核对/月报核对/经营分析会/供应商对账等',
  task_name        varchar(64)   NOT NULL COMMENT '任务名',
  cycle_rule       varchar(32)   NOT NULL COMMENT '周期规则:每月1日/每月2日/每月5日/每周一等',
  owner_role       varchar(16)   DEFAULT NULL COMMENT '责任角色:BOSS/FINANCE/REPLENISH',
  due_period       char(7)       DEFAULT NULL COMMENT '当前期(实例化后)',
  task_status      varchar(16)   NOT NULL DEFAULT '待办' COMMENT '状态:待办/进行中/已完成/已跳过',
  last_result      varchar(255)  DEFAULT NULL COMMENT '上次执行结果摘要(任务日历"上次结果"列)',
  auto_check_rule  varchar(255)  DEFAULT NULL COMMENT '完成校验规则(系统自动验:如"本月stocktake已完成≥5张")',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_routine_key (tenant_id, task_key, due_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定任务引擎:月度财务日历(§10.2)与任务日历页的数据源;完成校验系统自动验';

-- 36. 改价留痕
CREATE TABLE yc_vend_price_log (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  product_id       bigint        NOT NULL COMMENT '商品SKU',
  old_price        decimal(12,4) DEFAULT NULL COMMENT '原售价',
  new_price        decimal(12,4) NOT NULL COMMENT '新售价',
  change_source    varchar(16)   NOT NULL DEFAULT '手工' COMMENT '来源:手工/导入侦测(P2-9:sale_record单价≠档案售价→弹确认更新+写本表)',
  import_batch_id  bigint        DEFAULT NULL COMMENT '侦测来源批次',
  effect_date      date          DEFAULT NULL COMMENT '生效日(调价前后14天销量毛利对比,定价PDCA的C)',
  confirm_by       bigint        DEFAULT NULL COMMENT '确认人',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_plog_product (product_id, effect_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='改价留痕:喂饱定价PDCA;后台改价靠导入侦测补登记';

-- 37. 操作日志(全量,不可删)
CREATE TABLE yc_vend_op_log (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  user_id          bigint        NOT NULL COMMENT '操作人(SSO用户ID)',
  user_name        varchar(64)   DEFAULT NULL COMMENT '操作人姓名快照',
  action           varchar(32)   NOT NULL COMMENT '动作:新建/修改/确认/红冲/导入/回滚/锁账/改参数等',
  target_type      varchar(32)   NOT NULL COMMENT '对象类型(doc_head/product/replenish_config…)',
  target_id        bigint        DEFAULT NULL COMMENT '对象ID',
  before_json      text          COMMENT '改动前值',
  after_json       text          COMMENT '改动后值',
  op_time          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除(本表业务上永不删)',
  PRIMARY KEY (id),
  KEY idx_oplog_target (target_type, target_id),
  KEY idx_oplog_user (user_id, op_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志:任何单据详情可看"谁建的、谁确认的、谁改过什么";员工离职日志永久保留';

-- 38. 凭证附件(§9.4)
CREATE TABLE yc_vend_attachment (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id        varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  ref_type         varchar(32)   NOT NULL COMMENT '关联对象类型:doc_head/payment/settlement/claim/deduction/expense/stocktake',
  ref_id           bigint        NOT NULL COMMENT '关联对象ID',
  att_type         varchar(24)   NOT NULL COMMENT '凭证类型:转账截图/平台账单/赔付凭证/发票/照片。规则(§9.1):无凭证不能进"已结算";AI凭证识别(接入点#2)自动提取金额日期预填',
  file_path        varchar(500)  NOT NULL COMMENT '文件存储路径',
  file_name        varchar(255)  DEFAULT NULL COMMENT '原文件名',
  ocr_json         text          COMMENT 'AI识别结果(金额/日期/对方,Qwen-VL)',
  llm_call_id      bigint        DEFAULT NULL COMMENT 'AI调用记录',
  upload_by        bigint        DEFAULT NULL COMMENT '上传人',
  create_user      bigint        DEFAULT NULL COMMENT '创建人',
  create_dept      bigint        DEFAULT NULL COMMENT '创建部门',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user      bigint        DEFAULT NULL COMMENT '更新人',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status           tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_att_ref (ref_type, ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='凭证附件:凭证强制留痕(五条铁律之四)';

-- 39. AI 调用记录
CREATE TABLE yc_vend_llm_call_log (
  id                 bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id          varchar(64)   NOT NULL DEFAULT '000000' COMMENT '租户ID',
  scene              varchar(32)   NOT NULL COMMENT '接入点场景:别名归集/凭证识别/补货解释/异常侦探/月报起草/NL查数/PDCA起草/选品推荐/导入自愈/议价助手/拍照盘点/对话入口',
  model              varchar(64)   NOT NULL COMMENT '模型(可配置不写死:DeepSeek-V3/Qwen-VL/Kimi-K2/Qwen-Plus…)',
  prompt_fingerprint varchar(64)   DEFAULT NULL COMMENT 'Prompt模板指纹(版本追踪,可fork)',
  idempotent_key     varchar(128)  DEFAULT NULL COMMENT '幂等键(业务key+日期):24h窗口内命中直接复用,不重复烧钱',
  cache_hit          tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否缓存命中',
  input_digest       text          COMMENT '输入摘要/原始数据引用(透明四件套之原始数据)',
  reasoning          text          COMMENT '推理过程(透明四件套)',
  output_text        text          COMMENT '完整输出(透明四件套)',
  confidence         decimal(8,4)  DEFAULT NULL COMMENT '置信分(透明四件套;=validCount/N真算)',
  tokens_in          int           DEFAULT NULL COMMENT '输入token',
  tokens_out         int           DEFAULT NULL COMMENT '输出token',
  cost_amount        decimal(12,4) DEFAULT NULL COMMENT '本次成本(元)',
  duration_ms        int           DEFAULT NULL COMMENT '耗时毫秒',
  call_status        varchar(16)   NOT NULL DEFAULT '成功' COMMENT '状态:成功/失败/降级',
  create_user        bigint        DEFAULT NULL COMMENT '创建人',
  create_dept        bigint        DEFAULT NULL COMMENT '创建部门',
  create_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_user        bigint        DEFAULT NULL COMMENT '更新人',
  update_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  status             tinyint(1)    NOT NULL DEFAULT 1 COMMENT '记录状态',
  is_deleted         tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_llm_scene (tenant_id, scene, create_time),
  KEY idx_llm_idem (idempotent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI调用记录:统一网关落库;透明四件套(过程/输出/置信/原始数据)+24h idempotent。LLM永不直接算库存和补货量';

-- =====================================================================
-- 说明:
-- * 成本调整单(P0-1 单价错)不独立建表,复用 doc_head(doc_type=成本调整)+doc_item
--   (unit_price 存新单价, remark 存拆分说明);未售部分调 stock_ledger.unit_cost,
--   已售部分写 cash_flow(pl_line=成本调整),不追溯已产生的 sale_record.cost_amount。
-- * 资金调整单(P1-7 钱盘差异唯一出口)同样复用 doc_head(doc_type=资金调整),
--   老板确认后生成 cash_flow(pl_line=不入利润表-资金调整)。
-- * 期初余额/期初库存走 doc_type=期初 + 上线向导,总额与老账核对相符才生效。
-- =====================================================================
