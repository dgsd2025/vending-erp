-- M4-5 AI 接入点全家桶:NL 查数(接入点#6)只读白名单视图
-- 铁律:问数只允许 SELECT 这 5 个视图(NlQueryService 白名单硬校验),LLM 生成的 SQL 永不直接碰业务表。
-- 列名用中文别名:查询结果直接给老板看,不用再翻译。

-- 视图1:月毛利(按 月份 × 机器;口径 §13.1 定死:毛利 = 实收 − 移动加权成本,仅 正常+退款)
CREATE OR REPLACE VIEW v_ai_month_gross AS
SELECT s.biz_period                                            AS 月份,
       COALESCE(m.machine_name, '未知机器')                     AS 机器,
       SUM(s.amount_received)                                  AS 销售额,
       SUM(COALESCE(s.cost_amount, 0))                         AS 成本,
       SUM(s.amount_received - COALESCE(s.cost_amount, 0))     AS 毛利
FROM yc_vend_sale_record s
LEFT JOIN yc_vend_machine m ON m.id = s.machine_id
WHERE s.is_deleted = 0
  AND s.order_type IN ('正常', '退款')
GROUP BY s.biz_period, COALESCE(m.machine_name, '未知机器');

-- 视图2:机器日销(销量口径=正常+兑换 真消耗库存;金额口径=正常+退款 实收)
CREATE OR REPLACE VIEW v_ai_machine_daily_sales AS
SELECT DATE(s.biz_time)                                                          AS 日期,
       COALESCE(m.machine_name, '未知机器')                                       AS 机器,
       SUM(CASE WHEN s.order_type IN ('正常', '兑换') THEN s.qty ELSE 0 END)      AS 销量件数,
       SUM(CASE WHEN s.order_type IN ('正常', '退款') THEN s.amount_received ELSE 0 END) AS 实收金额
FROM yc_vend_sale_record s
LEFT JOIN yc_vend_machine m ON m.id = s.machine_id
WHERE s.is_deleted = 0
  AND s.order_type IN ('正常', '兑换', '退款')
GROUP BY DATE(s.biz_time), COALESCE(m.machine_name, '未知机器');

-- 视图3:库存现状(两级库存最新结存;取每 商品×位置×机器 按业务时间最新的一条流水)
CREATE OR REPLACE VIEW v_ai_stock_current AS
SELECT t.location_type                                              AS 位置,
       COALESCE(m.machine_name, '')                                 AS 机器,
       p.product_name                                               AS 商品,
       t.balance_qty                                                AS 结存数量,
       ROUND(t.balance_qty * COALESCE(t.unit_cost, p.ref_cost, 0), 2) AS 成本金额
FROM (
    SELECT l.*,
           ROW_NUMBER() OVER (PARTITION BY l.product_id, l.location_type, COALESCE(l.machine_id, 0)
                              ORDER BY l.biz_time DESC, l.id DESC) AS rn
    FROM yc_vend_stock_ledger l
    WHERE l.is_deleted = 0
) t
JOIN yc_vend_product p ON p.id = t.product_id
LEFT JOIN yc_vend_machine m ON m.id = t.machine_id
WHERE t.rn = 1
  AND t.balance_qty <> 0;

-- 视图4:损耗(已完成盘点的盘亏行,按 月份×原因;线下豁免行不算损耗,同 loss-stats 口径)
CREATE OR REPLACE VIEW v_ai_loss AS
SELECT DATE_FORMAT(st.snapshot_time, '%Y-%m')  AS 月份,
       COALESCE(i.diff_reason, '未填原因')      AS 原因,
       COUNT(*)                                AS 行数,
       SUM(-i.diff_qty)                        AS 损耗件数,
       SUM(-COALESCE(i.diff_amount, 0))        AS 损耗金额
FROM yc_vend_stocktake_item i
JOIN yc_vend_stocktake st ON st.id = i.stocktake_id
WHERE i.is_deleted = 0
  AND st.is_deleted = 0
  AND st.st_status = '已完成'
  AND i.diff_qty < 0
  AND i.offline_exempt = 0
GROUP BY DATE_FORMAT(st.snapshot_time, '%Y-%m'), COALESCE(i.diff_reason, '未填原因');

-- 视图5:资金流水汇总(按 入账月×方向×类别;金额恒正,方向看"方向"列)
CREATE OR REPLACE VIEW v_ai_cash_summary AS
SELECT f.book_period AS 月份,
       f.direction   AS 方向,
       f.category    AS 类别,
       SUM(f.amount) AS 金额,
       COUNT(*)      AS 笔数
FROM yc_vend_cash_flow f
WHERE f.is_deleted = 0
GROUP BY f.book_period, f.direction, f.category;
