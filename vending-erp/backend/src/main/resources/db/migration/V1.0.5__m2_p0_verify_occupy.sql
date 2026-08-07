-- 盲审 P0-1 修复:核销窗口重复计数 → 转移单与配货单 1:1 占用制
-- 问题:核销时按"±48h 窗口内全部导入转移单汇总"取实上架量,日常班次(每天一单一导入)下
--       同一张转移单被相邻几张配货单重复授信,带回率被系统性算成 0。
-- 修法:verify_doc_id 从"追溯指针"升级为"占用登记"——一张导入转移单最多被一张配货单核销占用,
--       核销取数只看被占用的那一张单;唯一索引兜底并发(MySQL 唯一索引允许多个 NULL,未核销单不受影响)。

-- 历史数据先去重:同一转移单被多张配货单占用的,保留最早一张(id 最小)的占用,其余清空
-- (被清空的单据状态/带回率保留,可通过"手动核销"按新 1:1 规则重算)
UPDATE yc_vend_prekit_ticket t
JOIN (
  SELECT verify_doc_id, MIN(id) AS keep_id
  FROM yc_vend_prekit_ticket
  WHERE verify_doc_id IS NOT NULL
  GROUP BY verify_doc_id
  HAVING COUNT(*) > 1
) d ON t.verify_doc_id = d.verify_doc_id AND t.id <> d.keep_id
SET t.verify_doc_id = NULL;

ALTER TABLE yc_vend_prekit_ticket
  ADD UNIQUE KEY uk_verify_doc (verify_doc_id);
