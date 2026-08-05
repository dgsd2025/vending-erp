# 智慧园区 · 园区自动售卖机 ERP — DESIGN_DOC(M1-1)

> 版本:v1.0 · 2026-08-06
> 依据:调研报告 v1.8(**§13 修正案效力最高**)+ 穿行审计报告(P0×6 / P1 / P2 全落地)+ UI Mockup V15(16 页)+ 冲刺 0 事实(一码多品 6 组)
> 配套 DDL:`vending-erp/docs/schema-draft.sql`(39 张表,MySQL 8,一次建齐)
> 决策记录:2026-08-05 用户拍板全量开发(拒绝一期切割),2-3 个月,分 4 里程碑交付;功能全开发、开关分批启用,Excel 并行 1 个月对账。

---

## ① 系统架构一页图(文字版)

```
┌─────────────────────────── 智慧园区主系统(SSO 身份/消息通道) ───────────────────────────┐
│                                                                                        │
│  前端 Vue 3 + Vite(16 页:驾驶舱/AI补货/库存/采购/出库/BI/资金/供应商/盘点/资产/PDCA/设置…) │
│        │ REST/JSON                                                                     │
│  后端 Spring Boot 2.7 + JDK8(ole 平台主栈) ── MySQL 8(yc_vend_ 前缀 39 表)             │
│        │                                                                               │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────────────┐                │
│  │ 导入中心      │   │ 规则引擎      │   │ AI 网关(OpenAI 兼容协议)      │                │
│  │ =数据入口     │   │ =出数字       │   │ =出解释                       │                │
│  └──────────────┘   └──────────────┘   └──────────────────────────────┘                │
└────────────────────────────────────────────────────────────────────────────────────────┘
        ▲ 三类导出文件(手工下载→上传导入)
  售卖机后台 fanmaiji.top(广州易普乐 SaaS):出货明细 / 系统补货记录 / 商品列表
```

五句话说清:

1. **导入中心是全系统数据入口**:销售(出货明细)、出库上架(系统补货记录)、SKU 别名(商品列表)三通道全部走后台导出文件导入;日常手工只录"采购入库"一种单据。批次可整批回滚,原始文件归档。
2. **规则引擎出数字**:(R,S) 补货模型、移动加权成本、两级库存推算、三大公式(资产/利润/应付)——全部确定性计算,每个数字可点开看公式与输入值(`replenish_plan.formula_json`)。
3. **LLM 出解释**:12 个接入点(别名归集/凭证识别/补货解释…)统一走 AI 网关,模型可配置,24h idempotent,透明四件套全落 `llm_call_log`。**LLM 永不直接算库存和补货量**。
4. **单据驱动一切**:所有库存变动唯一由单据产生(`stock_ledger.doc_id` 非空),所有资金流水唯一由单据产生(`cash_flow.ref_doc_id`),单据不许删只许红冲。
5. **身份复用园区 SSO**:不自建账号密码,模块内 4 个轻角色(老板/财务/补货员/录单员)存 `user_role`,双签靠角色天然实现,全量操作落 `op_log`。

---

## ② 模块清单与里程碑归属

**策略:全量表一次建齐(39 张,M2-M4 的表现在就建),功能按里程碑分期实现。**

| 里程碑 | 模块 | 覆盖页面(Mockup V15) |
|---|---|---|
| **M1 数据地基** | 主数据(商品/别名/供应商/机器/货道/角色)、单据引擎(doc_head+doc_item 状态机)、导入中心三通道、两级库存流水、期初向导(含一码多品清洗) | 设置中心、库存管理、采购入库、出库上架、单品详情、机器详情 |
| **M2 补货闭环** | (R,S) 补货引擎、补货参数、Pre-kit 配货单+核销带回率、采购建议、轻量订货单(在途)、手机版补货页 | AI 补货提示、任务日历(补货部分) |
| **M3 钱账** | 账户/流水、付款单、应付结算单(含红字)、平台结算单、抵扣确认单、索赔单、支出单、盘点(五步向导)、资产快照、锁账、凭证附件 | 资金与对账、供应商往来、盘点、资产家底 |
| **M4 BI-PDCA-AI** | BI 矩阵(四象限/单机对比/时段热力)、月度报表包+AI 月报、PDCA 六环节看板、改进任务、固定任务引擎、12 个 AI 接入点全量上线 | BI 经营分析、改进循环 PDCA、经营驾驶舱、人员详情 |

---

## ③ 全量表清单(39 张)

> 完整字段见 `vending-erp/docs/schema-draft.sql`;此处列 表名/中文名/关键字段/口径规则/里程碑。

### 主数据区(M1)

| # | 表 | 中文名 | 关键字段 | 口径规则 |
|---|---|---|---|---|
| 1 | yc_vend_product | 商品档案 | sku_code(uk)、**legacy_code**(一码多品拆分留痕)、box_spec 箱规、shelf_life_days、**ref_cost 参考成本**、ref_price 参考售价、**product_status(在售/清仓中/停售)** | 售价仅参考价(毛利按实收算);无采购史毛利显"—",禁 0 参与加权;清仓中禁采购/补货建议;停售≠删除 |
| 2 | yc_vend_sku_alias | SKU 别名映射 | **uk=(alias_code 后台商品编号+alias_barcode 条码)**,alias_name 仅留痕 | 冲刺 0 拍板:不绑名称;绑一次终身生效 |
| 3 | yc_vend_supplier | 供应商 | settle_method、account_days、opening_payable | 应付余额=期初+Σ采购−Σ退货−Σ抵扣−Σ付款,实时算不落表 |
| 4 | yc_vend_machine | 机器 | **device_id(后台设备 ID,uk)**、machine_status | 与后台对齐的锚点;撤点先退库单再停用 |
| 5 | yc_vend_slot | 货道 | uk=(machine_id,slot_no)、capacity、current_qty | 机器层 S 硬上限=货道数×容量;current_qty 为推算值,权威在后台 |
| 6 | yc_vend_user_role | 人员角色 | user_id(SSO)、role_code(老板/财务/补货员/录单员) | 双签(录单人≠付款人)靠角色实现 |

### 单据区(M1)

| # | 表 | 中文名 | 关键字段 | 口径规则 |
|---|---|---|---|---|
| 7 | yc_vend_doc_head | 单据头 | doc_no(uk)、**doc_type 十枚举(采购入库/出库上架/退库/盘盈入库/盘亏出库/报损/红冲/成本调整/资金调整/期初)**、**doc_status 状态机(草稿/预挂单/待确认/已确认/待结算/已结算/已完成/已红冲/已作废)**、biz_date、**red_flush_of**、**matched_doc_id**、**neg_stock_exempt**、confirm_by/at+confirm2_by/at、due_date、book_period、doc_source、import_batch_id | 单据不许删只红冲;转移单唯一生产者=导入;手工转移单一律预挂单 |
| 8 | yc_vend_doc_item | 单据明细 | qty/box_qty、unit_price、**expect_qty 应收列**、batch_no/expire_date、po_item_id | 收货差异=实收−应收自动标注;FEFO 用批次到期日 |
| 9 | yc_vend_purchase_order | 轻量订货单 | po_no、expect_date、po_status | 草稿级不进账;超期未到黄灯 |
| 10 | yc_vend_purchase_order_item | 订货明细 | **qty_ordered/qty_received** | **在途=Σ(订购−已收),唯一数据生产者** |

### 销售与导入区(M1)

| # | 表 | 中文名 | 关键字段 | 口径规则 |
|---|---|---|---|---|
| 11 | yc_vend_sale_record | 销售记录 | **order_no(uk 去重键)**、alias_code_raw/alias_name_raw、**order_type 五枚举(正常/兑换/退款/测试/线下补录)**、amount_received、**biz_time 业务时间戳**、**biz_period/book_period**、cost_amount、settlement_id(可空=在途货款)、offline_flag | **三口径见 ⑤**;后台已有出货禁手工再录出库 |
| 12 | yc_vend_import_batch | 导入批次 | file_type 四通道、archive_path、row_total/ok/fail/dup、batch_status(可整批回滚)、column_map_json | 期初通道带一码多品清洗;出货明细每月至少导一次 |
| 13 | yc_vend_import_error | 行级错误 | error_type(含"编码冲突")、resolve_status | 失败行留痕可重导 |
| 14 | yc_vend_alias_pending | 别名待绑定队列 | uk=(alias_code,alias_barcode)、suggest_product_id+ai_confidence | AI 建议、人只点确认;绑定后回补 sale_record |

### 库存区(M1)

| # | 表 | 中文名 | 关键字段 | 口径规则 |
|---|---|---|---|---|
| 15 | yc_vend_stock_ledger | 库存流水 | location_type(仓库/机器)、**doc_id 非空**、change_qty/balance_qty、unit_cost、biz_time | 库存唯一由单据产生;期初期末全由流水推算 |
| 16 | yc_vend_machine_stock_snapshot | 机器库存快照 | snapshot_source(后台缺货页/盘点/补货记录)、snapshot_time | **机器库存=最近快照+快照后按业务时间戳增量**(与导入顺序无关) |

### 补货区(M2)

| # | 表 | 中文名 | 关键字段 | 口径规则 |
|---|---|---|---|---|
| 17 | yc_vend_replenish_config | 补货参数 | cycle_days(R)、service_level、lead_time_days(L),全局+SKU/机器覆盖 | 改参数写 op_log,下周期对比 |
| 18 | yc_vend_replenish_plan | 补货建议 | target_level_s、safety_stock、suggest_qty、box_round_qty、**formula_json**、ai_explain+llm_call_id | S=d̄×(R+L)+SS;货道容量硬约束;日均口径=正常+兑换 |
| 19 | yc_vend_prekit_ticket | 配货单 | ticket_status(已生成/已执行/已核销/有差异)、verify_doc_id、**takeback_rate** | 核销=次日匹配导入转移单,差量=带回率(PDCA 唯一数据源) |
| 20 | yc_vend_prekit_ticket_item | 配货明细 | qty_planned/qty_loaded/qty_takeback | — |

### 钱账区(M3)

| # | 表 | 中文名 | 关键字段 | 口径规则 |
|---|---|---|---|---|
| 21 | yc_vend_account | 资金账户 | account_type(真实 4 类+虚拟 2 类)、is_virtual、opening_balance(**只能设一次**) | 余额=期初+Σ流水实时推算 |
| 22 | yc_vend_cash_flow | 资金流水 | **pl_line 利润表行映射**、ref_doc_type/ref_doc_id(**必填**)、book_period | **全系统唯一钱账**;每类流水在利润表有且只有一个去处 |
| 23 | yc_vend_payment | 付款单 | deduction_amount 兑换抵扣额、pay_status(含差异挂起) | 无转账截图不能进已付款;确认人≠录单人 |
| 24 | yc_vend_settle_bill | 结算单 | **direction(正常/红字)**、amount_due/deduction_amount/amount_actual、lock_diff_note | 红字冲下一单(红冲连锁应付侧);锁后差异只提示不改状态 |
| 25 | yc_vend_settlement | 平台结算单 | platform_amount/fee_amount/actual_amount/system_amount、**diff_sales/diff_arrival 双差异** | 系统额口径=仅正常(退款为负);核销回填 sale_record |
| 26 | yc_vend_deduction | 抵扣确认单 | **supplier_id 必填**、ded_status(待抵扣/已用于结算单X) | 只允许带入同供应商结算单(防串户) |
| 27 | yc_vend_claim | 索赔单 | claim_status(**申请中/已到账/放弃**)、received_amount、cash_flow_id | 申请中计入资产"索赔应收";到账进"其他收入-赔付" |
| 28 | yc_vend_expense | 支出单 | category、is_equipment→equipment_id | 利润表"杂费"行来源;设备同步进台账 |

### 盘点与资产区(M3)

| # | 表 | 中文名 | 关键字段 | 口径规则 |
|---|---|---|---|---|
| 29 | yc_vend_stocktake | 盘点单 | scope_type(仓库/机器)、snapshot_time、gain_doc_id/loss_doc_id、source_task | 盘亏确认强制五步向导;差异>¥50 老板确认 |
| 30 | yc_vend_stocktake_item | 盘点明细 | book_qty/actual_qty/diff_qty、**diff_reason 六枚举**、claim_id、offline_exempt | 第 1 步查账系统自动做;线下销售差异豁免 |
| 31 | yc_vend_equipment | 设备台账 | buy_price、residual_value | 展示回本进度,**不进流水** |
| 32 | yc_vend_asset_snapshot | 资产快照 | inventory/platform_pending/cash/claim_receivable/payable/net_asset | 公式见 ⑤;归档永不重算 |
| 33 | yc_vend_period_lock | 锁账记录 | period(uk)、locked_by/at | 锁账只管改单不管补导;补导走 book_period |

### PDCA / 留痕 / AI 区(M4)

| # | 表 | 中文名 | 关键字段 | 口径规则 |
|---|---|---|---|---|
| 34 | yc_vend_action_item | 改进任务 | source_scene 六环节、verify_metric+verify_date、item_status、ai_draft | 所有 A 落此一表;到期自动回查 |
| 35 | yc_vend_routine_task | 固定任务引擎 | task_key、cycle_rule、auto_check_rule、last_result | 月度财务日历/任务日历页数据源 |
| 36 | yc_vend_price_log | 改价留痕 | old/new_price、change_source(导入侦测/手工)、effect_date | 调价前后 14 天对比(定价 PDCA) |
| 37 | yc_vend_op_log | 操作日志 | before_json/after_json | 全量不可删;单据详情"谁改过什么" |
| 38 | yc_vend_attachment | 凭证附件 | ref_type/ref_id、att_type、ocr_json | 无凭证不能进已结算;AI 识别预填 |
| 39 | yc_vend_llm_call_log | AI 调用记录 | scene 十二接入点、model、idempotent_key、reasoning/output/confidence/input_digest | 透明四件套+24h idempotent;LLM 永不算库存补货量 |

**不独立建表的三件事**(复用 doc_head+doc_item):成本调整单(doc_type=成本调整)、资金调整单(doc_type=资金调整)、期初单(doc_type=期初)。

---

## ④ P0 六条修正如何落地 + 穿行 14 场景自查

### P0 六条 → 表/字段/规则

| P0 | 修正 | 落地位置 |
|---|---|---|
| **P0-1 红冲连锁规则集** | 数量错→整单红冲(限无下游);单价错→成本调整单;已付款→应付红字;红冲免负库存拦截+强制影响清单确认页 | `doc_head.red_flush_of` + `doc_type=红冲/成本调整` + `doc_head.neg_stock_exempt` + `settle_bill.direction=红字` + `stock_ledger.unit_cost`(未售调存货)+ `cash_flow.pl_line=成本调整`(已售进利润表,不追溯) |
| **P0-2 锁账×补导期间归属** | 业务月/入账月分离;锁后补导旧报表永不重算,"上期调整"行承接 | `sale_record.biz_period + book_period`、`cash_flow.book_period`、`doc_head.book_period`、`yc_vend_period_lock` 表、`settle_bill.lock_diff_note`(已核销单只提示) |
| **P0-3 order_type 三口径** | 结算口径=仅正常(退款负);补货日均=正常+兑换;毛利=实收−加权成本,兑换收入 0、成本由补贴对冲;后台已有出货禁手工再录 | `sale_record.order_type` 五枚举(COMMENT 写死三口径)、`settlement.system_amount` 只聚合正常/退款、`replenish_plan.avg_daily` 口径注释、`deduction`(兑换成本对冲载体)、保存时机器+SKU+日期碰撞检查(应用层规则) |
| **P0-4 转移单唯一生产者=导入** | 手工单一律预挂单,导入按机器+SKU+当日窗口匹配冲抵,差量记带回,超 48h 转正;首铺同规则 | `doc_head.doc_status=预挂单` + `doc_head.doc_source(手工/导入)` + `doc_head.matched_doc_id` + `prekit_ticket.verify_doc_id/takeback_rate` |
| **P0-5 轻量采购订单表** | 草稿级不进账;在途=Σ(订购−已收);收货带应收列;超期黄灯 | `yc_vend_purchase_order(+item)` 表、`purchase_order_item.qty_ordered/qty_received`、`doc_item.expect_qty/po_item_id`、`purchase_order.expect_date` |
| **P0-6 公式补漏** | 资产+=索赔应收;利润表加"其他收入"行;cash_flow 与利润表行一一映射;净损耗=损耗−已获赔 | `asset_snapshot.claim_receivable`、`claim.claim_status=申请中`、`cash_flow.pl_line`(枚举含 其他收入-赔付/平台外/补贴)、损耗报表=Σ盘亏−Σclaim 已到账(报表逻辑) |

### 穿行 14 场景 → 表/字段逐一自查

| # | 场景 | 用到的表/字段(全部已存在) |
|---|---|---|
| 1 | 正常补货日 | `prekit_ticket.ticket_status/verify_doc_id/takeback_rate` ← 核销匹配规则已定义(次日按机器+日期窗口匹配导入转移单) |
| 2 | 拿货日 | `purchase_order_item.qty_ordered/qty_received`(在途生产者)+ `doc_item.expect_qty`(收货应收列)+ `purchase_order.expect_date`(超期黄灯) |
| 3 | 月度盘点日·钱盘差异 | `doc_head.doc_type=资金调整`(唯一出口)→ `cash_flow(pl_line=不入利润表-资金调整)`;应付不符→补录或 `settle_bill.direction=红字` |
| 4 | 兑换出库 | `sale_record.order_type=兑换`(不入待结算:`settlement.system_amount` 不聚合;计入补货日均);`deduction`(补贴到账对冲成本);双扣库存由"后台出货禁手工录出库"碰撞检查拦住 |
| 5 | 故障线下卖 | `sale_record.order_type=线下补录 + offline_flag=1` 复合单 → 同时生成 `cash_flow(pl_line=其他收入-平台外)` + `stocktake_item.offline_exempt=1`(机器账差异豁免) |
| 6 | 盘亏索赔到账 | `claim.claim_status 申请中→已到账` + `asset_snapshot.claim_receivable` + `cash_flow(pl_line=其他收入-赔付)` + 净损耗=损耗−已获赔 |
| 7 | 新商品后台先卖 | `product.ref_cost`(无采购史毛利显"—",`sale_record.cost_amount=NULL` 禁 0 加权)+ `doc_head.neg_stock_exempt=1`(导入豁免拦截+"待补录采购"红灯,手工不豁免) |
| 8 | 新机首次铺货 | 首铺=去后台执行补货→次日导入自动生成铺货转移单(`doc_source=导入`);手工铺货单走 `doc_status=预挂单` + `matched_doc_id` 冲抵,不双扣 |
| 9 | 淘汰清仓剩货 | `product.product_status=清仓中` + `clearance_since`(仓库>0 超 30 天三选一:退库单退供/报损单/换机转移),死锁解除:清仓中允许上架/退货/报损 |
| 10 | 红冲连锁 | 见 P0-1 行(red_flush_of + 成本调整 + 红字 + neg_stock_exempt + 影响清单确认页) |
| 11 | 漏导 3 天/乱序导入 | `sale_record.biz_time` + `stock_ledger.biz_time` + `machine_stock_snapshot`(最近快照+快照后按业务时间戳增量,与导入顺序无关);`import_batch` 防重(order_no uk)可回滚 |
| 12 | 后台改价未登记 | 毛利口径写死=实收(`sale_record.amount_received`);`sale_record.unit_price≠product.ref_price` → 侦测弹确认 → `price_log(change_source=导入侦测)` |
| 13 | 供应商切换期 | `deduction.supplier_id NOT NULL` + 结算单只带同供应商待抵扣(`settle_bill.deduction_amount` 校验规则) |
| 14 | 锁账后补导漏单 | `period_lock` + `sale_record.book_period=当月`(旧报表永不重算)+ 利润表"上期调整"行(报表按 book_period 聚合,biz_period≠book_period 即为上期调整)+ `settle_bill.lock_diff_note` |

---

## ⑤ 口径规则集中区(§13 原样抄录 · **效力最高**)

> 以下条款抄自调研报告 v1.8 §13「穿行审计修正案」,**与本文档或任何前文冲突时以本节为准**。

### §13.1 公式修正(直接覆盖前文)

- **资产快照(修 §7.4)**:净流动资产 = 库存(成本)+ 平台待结算 + 账户现金 + **索赔应收(claim 申请中)** − 应付供应商。
- **简版利润表(修 §10.3)**:毛利 − 平台手续费 − 杂费 − 损耗 + **其他收入(赔付/平台外收入/厂家补贴)** = 经营利润;cash_flow 类别与利润表行建立一一映射,每类流水在利润表有且只有一个去处;净损耗 = 损耗 − 已获赔。
- **毛利口径(定死)**:毛利 = sale_record 实收金额 − 移动加权成本;product.售价 仅为参考价(后台侦测更新 + price_log 留痕)。

### §13.2 规则修正(覆盖 §9/§11 对应条款)

1. **红冲连锁**:数量错→整单红冲(限无下游动作);单价错→"成本调整单"(未售调存货金额/已售进利润表"成本调整"行,不追溯);已付款→应付红字(settle_bill.direction)冲下一单;红冲免负库存拦截,必过"影响清单"确认页。
2. **锁账×补导**:sale_record 加 biz_period/book_period;锁后补导→旧报表永不重算,当月"上期调整"行承接;已核销结算单仅提示"锁后新增差异"。
3. **order_type 三口径**:结算对账口径=仅正常(退款为负);补货日均口径=正常+兑换;测试不计;后台已有出货禁止手工再录出库(机器+SKU+日期碰撞检查)。
4. **转移单唯一生产者=后台补货记录导入**;手工单一律"预挂单",导入按机器+SKU+当日窗口匹配冲抵,超 48h 无后台记录才转正;首铺同规则。
5. **新增单据/表**:purchase_order(轻量订货单,在途=Σ未收,收货带"应收"列)、资金调整单(钱盘差异唯一出口)、prekit_ticket(配货单落表,核销差量=带回率数据源)、"清仓中"商品状态(仓库残余>30 天三选一:退供/报损/换机)。
6. **字段补**:deduction+supplier_id(防串户);product+参考成本(无采购史毛利显"—",禁用 0 参与加权);导入生成的转移单遇仓库不足→允许负库存+"待补录采购"红灯(导入豁免拦截,手工不豁免)。
7. **改价侦测**:导入校验单价≠档案售价→确认更新+写 price_log(喂定价 PDCA)。

### 补充口径(来自 §7/§9/§11,与 §13 不冲突)

- **应付余额** = 期初欠款 + Σ采购 − Σ退货 − Σ兑换/补贴抵扣 − Σ付款(实时推算,不落静态余额表)。
- **锁账规则**:每月出完报表后锁账(默认 5 日);锁定期之前单据不许改,红冲需老板角色+强制备注;锁账只管改单不管补导入,补导走 book_period 口径。
- **单据即台账**:钱不允许"直接记一笔",任何流水必须由单据生成;单据不许删只许红冲;无凭证不能进"已结算"。
- **§13.3 可用性验收预算**:录单员 ≤10 分钟/天 · 补货员 ≤2 分钟/台 · 老板 ≤5 分钟/天;超时 = P1 缺陷。

---

## ⑥ 导入三通道数据流

> 后台(fanmaiji.top)三类导出文件 → 导入中心。每批落 `import_batch`(原始文件归档,可整批回滚),行错落 `import_error`。

### 通道 1:出货明细 → sale_record(销售)

```
上传文件 → 解析 13 字段 → 逐行处理:
  ① 防重:order_no 命中 uk_sale_order → 跳过(row_dup++),重复导入零副作用
  ② 机器映射:设备ID → machine(找不到→行错误)
  ③ 别名归集:后台商品编号+条码 查 sku_alias(冲刺0拍板:不按名称)
       命中 → 回填 product_id
       未命中 → 入 alias_pending(AI 向量召回+LLM 复核给建议),行照常入库 product_id=NULL,绑定后回补
  ④ 口径打标:order_type 原样落库;biz_period=按出货时间;book_period=锁账判断(已锁→当月)
  ⑤ 改价侦测:单价≠product.ref_price → 收集,导入完成后弹确认 → 更新档案+写 price_log
  ⑥ 成本快照:按移动加权写 cost_amount(无采购史=NULL)
```
- **去重键**:`(tenant_id, order_no)` 唯一索引。
- **导入窗口**:后台查询限当月+上月 → 每月至少导一次;乱序/漏导按 biz_time 重算,与导入顺序无关。

### 通道 2:系统补货记录 → 出库上架转移单(唯一生产者)

```
上传文件 → 按 机器+补货时间 聚合成转移单(doc_type=出库上架, doc_source=导入):
  ① 防重:同机器+货道+补货时间戳已存在 → 跳过
  ② 负数补货数(如 −4)= 取出调整 → 生成逆向转移(机器→仓库)
  ③ 预挂单冲抵:按 机器+SKU+当日窗口 匹配手工预挂单 → 冲抵(matched_doc_id),差量记带回;
     超 48h 无后台记录的预挂单才转正
  ④ 配货单核销:按 机器+日期窗口 匹配 prekit_ticket → 回填 qty_loaded/qty_takeback → 算带回率
  ⑤ 库存落账:仓库 −/机器 + 写 stock_ledger;仓库不足 → 允许负库存+"待补录采购"红灯
     (导入豁免拦截 neg_stock_exempt=1,手工单不豁免)
  ⑥ 机器快照:写 machine_stock_snapshot(source=补货记录)
```

### 通道 3:商品列表 → sku_alias 初始化/增量

```
上传文件(98 商品:编号/条形码/名称/售价/分类) → 逐行:
  ① 按 后台商品编号+条码 查 sku_alias:已绑 → 检查售价变化(改价侦测);未绑 → 入 alias_pending
  ② AI 辅助:text-embedding 召回候选 SKU + LLM 复核置信度,人只点确认(接入点#1)
```

### 期初通道(上线一次性,doc_type=期初)

```
现有 Excel 套表 → 期初向导:商品档案/期初库存/期初应付/历史销售
  ★ 编码冲突清洗(冲刺0新增):检出一码多品(已知 6 组:SP009/010/011/012/046/069 同码挂两个商品)
     → 强制拆分为新码(如 SP009A/SP009B),原码存 product.legacy_code 追溯;未拆分 → import_error(编码冲突),整批不放行
  ★ 总额校验:期初库存金额/期初应付与老账相符才生效
```

---

## 附:遗留待裁决点(主窗口拍板)

1. **sale_record 去重键与退款**:按规格用 `uk(order_no)`;若后台"退款"是同订单号另起一行,会撞唯一键——待用真实退款导出样例验证,必要时改 `uk(order_no, order_type)`。
2. **出货明细是否含"商品编号"列**:通道 1 别名匹配主键改为 编号+条码,需冲刺 0 用真实导出文件确认出货明细 13 字段里有编号列;若只有名称+条码,则通道 1 以条码为主、名称兜底,通道 3(商品列表)仍按编号+条码。
3. **routine_task(固定任务引擎)**:Mockup 有"任务日历"页但调研报告未列此表,本设计新增第 35 张表支撑;如认为月度 SOP 可硬编码可裁掉。
4. **收货单/入库单一张还是两张**:§9.2 状态机里"收货单→入库单"按一张采购入库单的两个状态实现(expect_qty 应收列承载收货差异),未拆两张表。

---

## 附录 C:成本调整过账契约(M1-6×M1-7 并行开发约定,主窗口裁决 2026-08-06)

- 成本调整单(doc_type=成本调整)过账 = 向 stock_ledger 插入 **qty=0、amount=Δ金额** 的调整流水(未售部分调存货金额,§13.2-1)。
- 移动加权成本引擎按 ledger **时序遍历**:入库行 → 金额、数量同步累加重算单位成本;qty=0 调整行 → 只动金额,单位成本随之变;出库行按当前单位成本结转。已售部分不追溯,由"利润表成本调整行"承接(cash_flow.pl_line=成本调整,M3 实装,M1-7 先落 doc + ledger)。
- 无采购史 SKU:单位成本=NULL → 毛利显示"—(成本待补)",禁用 0 参与加权(§13.2-6);product.ref_cost 仅作展示兜底,不进加权。
