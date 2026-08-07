# Flyway 生产迁移清单(§4.16)

## 首次上线(全新空库)——最省心

新建的生产库(如 `vend_prod`)是**空的**,Flyway 会在后端首次启动时自动从 `V1.0.0` 一路跑到 `V1.0.17`(共 18 个迁移),建齐 40 张表 + 5 个 AI 视图 + 3 条任务种子。**无需手动 apply 任何 ALTER**——§4.16 的"手动 apply"针对的是"已有数据的表上加字段"的增量场景,首次全新库不适用。

首次上线只需确认:
1. 生产库已建好且为空:`CREATE DATABASE vend_prod CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`
2. `aole_uat` 用户对该库有 ALL PRIVILEGES
3. `.env` 里 `SPRING_FLYWAY_TABLE=flyway_schema_history_vend`(共库时防和其他产品的历史表撞名;独立库时也无害)
4. 后端启动后查:`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='vend_prod' AND table_name LIKE 'yc_vend_%';` 应为 40;`flyway_schema_history_vend` 最后一行 `1.0.17 success=1`

## 迁移清单(18 个,按序)

```
V1.0.0  init(39表基线)          V1.0.9  m3_4 索赔支出
V1.0.1  m1_3 导入                V1.0.10 m3_5 钱盘
V1.0.2  m1_7 红冲锁账            V1.0.11 m3_3 结算双模式
V1.0.3  m2 补货                  V1.0.12 m3_6 finreport
V1.0.4  m2_3 配货单              V1.0.13 m3_9 盲审修复(cash_flow.account_id 可空)★
V1.0.5  m2 核销占用              V1.0.14 m3_9 七律修复(red_flush_of + 任务种子)
V1.0.6  m2_6 任务                V1.0.15 m4_3 pdca
V1.0.7  m3_1 钱账config          V1.0.16 m4_5 ai视图
V1.0.8  m3_2 结算                V1.0.17 m4_8 confidence_source ★
```

## 后续增量部署(有数据后)——才需注意

上线运行、库里有数据后,若再发新版带**新迁移**:Flyway 仍会自动跑增量。但**加字段/改结构的 ALTER 若涉及大表或有锁风险**,按 §4.16:先手动在 prod 低峰 apply,再发版重启。★标的 V1.0.13/V1.0.17 就是这类"给现有表加列"的迁移——首次上线随基线一次建好无所谓,**记住这个模式用于将来**。
