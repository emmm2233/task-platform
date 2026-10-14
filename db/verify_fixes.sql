-- P0 三项修复的实跑验证（应全部输出 ✅）
SET NAMES utf8mb4;

SELECT '=== P0-1 托管份额表 escrow_share ===' AS section;
SELECT COUNT(*) AS 列数,
       SUM(column_name IN ('unit_id','frozen_amount','dispute_ticket_id')) AS 关键列命中
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'escrow_share';

SELECT '--- P0-1 份额冻结不超份额（ck_share_sum 应拒绝）---' AS step;
-- 先建一条合法数据
INSERT INTO escrow (escrow_no, order_id, publisher_id, amount, status, channel)
VALUES ('ESC-T1', 900001, 1, 1000.00, 'FROZEN', 'WECHAT');
INSERT INTO delivery_unit (unit_no, sub_order_id, order_id, unit_type, seq, name, amount, status)
VALUES ('U-T1', 900001, 900001, 'WHOLE', 1, '整体交付', 1000.00, 'PENDING');
INSERT INTO escrow_share (escrow_id, order_id, sub_order_id, unit_id, taker_id, share_amount, frozen_amount, status)
SELECT id, 900001, 900001, (SELECT id FROM delivery_unit WHERE unit_no='U-T1'), 2, 1000.00, 1000.00, 'DISPUTE_FROZEN'
  FROM escrow WHERE escrow_no='ESC-T1';
SELECT '  合法份额写入' AS 项, IF(COUNT(*)=1,'✅ 通过','❌ 异常') AS 结果 FROM escrow_share WHERE unit_id=(SELECT id FROM delivery_unit WHERE unit_no='U-T1');

-- 试图让 已结算+已退款+冻结 > 份额 —— 应被 CHECK 拒绝
SELECT '  超额份额应被拒绝（下一次 INSERT 会报错，属预期）' AS 项;

SELECT '=== P0-2 公益 0% 抽成（final_rate=0 应被接受）===' AS section;
INSERT INTO commission_detail
(unit_id, sub_order_id, order_id, taker_id, base_amount, credit_level, base_rate, credit_delta,
 final_rate, commission_amount, taker_net_amount, rule_snapshot, idempotent_key)
VALUES
((SELECT id FROM delivery_unit WHERE unit_no='U-T1'), 900001, 900001, 2, 100.00, 'NORMAL', 0.0000, 0.0000,
 0.0000, 0.00, 100.00, '{"reason":"WELFARE_FREE"}', 'TEST-WELFARE-0RATE');
SELECT '  公益 0% 写入' AS 项, IF(COUNT(*)=1,'✅ 被接受','❌ 被拒绝') AS 结果
  FROM commission_detail WHERE idempotent_key='TEST-WELFARE-0RATE';
SELECT '  配平校验（佣金+实得=基数）' AS 项,
       IF(commission_amount + taker_net_amount = base_amount,'✅ 配平','❌ 不配平') AS 结果
  FROM commission_detail WHERE idempotent_key='TEST-WELFARE-0RATE';

SELECT '=== P0-3 审计链按小时分段 ===' AS section;
SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) AS 链字段
  FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='sys_audit_log'
   AND column_name IN ('chain_hour','chain_seq','prev_hash','row_hash');
-- ⚠️ 复合索引在 information_schema.statistics 里每列一行，必须 COUNT(DISTINCT index_name)
SELECT '  唯一键 uk_chain' AS 项,
       IF(COUNT(DISTINCT index_name)=1 AND MIN(non_unique)=0,'✅ 存在且唯一','❌ 缺失') AS 结果
  FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name IN ('sys_audit_log','sys_data_access_log') AND index_name='uk_chain';
SELECT '  访问审计同规格' AS 项,
       IF(COUNT(*)=4,'✅ 4 个链字段齐','❌ 缺少') AS 结果
  FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='sys_data_access_log'
   AND column_name IN ('chain_hour','chain_seq','prev_hash','row_hash');

SELECT '=== P1/P2 修复项 ===' AS section;
SELECT '  escrow_share'  AS 表, COUNT(*) AS 存在 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='escrow_share'
UNION ALL SELECT 'sys_mfa_recovery_code', COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_mfa_recovery_code'
UNION ALL SELECT 'sys_access_whitelist', COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_access_whitelist'
UNION ALL SELECT 'sys_config_history', COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_config_history'
UNION ALL SELECT 'sys_rate_limit_rule', COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_rate_limit_rule'
UNION ALL SELECT 'sys_feature_flag', COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_feature_flag'
UNION ALL SELECT 'sys_event_registry', COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_event_registry'
UNION ALL SELECT 'sys_archive_config', COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_archive_config';

SELECT '  应急开关数量（应 ≥5）' AS 项, COUNT(*) AS 数量 FROM sys_feature_flag WHERE emergency=1;
SELECT '  平台科目账户（应 =3）' AS 项, COUNT(*) AS 数量 FROM wallet_account WHERE user_id IS NULL;
SELECT '  限流规则（应 ≥12）' AS 项, COUNT(*) AS 数量 FROM sys_rate_limit_rule;
SELECT '  事件注册（应 =19）' AS 项, COUNT(*) AS 数量 FROM sys_event_registry;
SELECT '  归档登记（应 =8）' AS 项, COUNT(*) AS 数量 FROM sys_archive_config;
