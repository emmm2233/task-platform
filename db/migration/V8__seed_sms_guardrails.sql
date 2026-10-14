-- =====================================================================
--  V8：短信防轰炸的两个补充闸门
--
--  背景（独立审查实测的两个真缺口）：
--    ① 现有四维限流里的 ip / deviceId 由【请求体】提供
--       ⇒ 每次请求随机填一个就能同时绕开两维，实际只剩"同号"一维；
--       而契约与 javadoc 都声称"四维（含全局）"，全局那一维在代码里【根本不存在】。
--       ⇒ 号码池 + 伪造设备的短信费用攻击没有上限。
--    ② verify 校验失败【不删码、不计数】⇒ 6 位码在 5 分钟窗口内可被暴力猜解
--       （100 万种可能，且没有尝试上限）。
--
--  ⛔ 门槛值一律走 sys_config，不写死在代码里（本项目红线）。
-- =====================================================================

INSERT INTO `sys_config` (`config_key`, `config_value`, `value_type`, `group_code`, `description`) VALUES
  ('security.sms_global_hour_limit',   '500', 'INT', 'SECURITY',
   '全站短信小时上限（分布式刷量的兜底闸门；只按手机号/IP/设备限流挡不住号码池攻击）。⚠️ 取值兼顾测试：反复跑测试套件会累积计数，所以留了余量 —— 生产按实际量级调小，并配合告警'),
  ('security.sms_verify_max_attempts', '5',   'INT', 'SECURITY',
   '同一验证码最多校验几次；超限即作废该码（防 6 位码被暴力猜解，需重新发码）');

-- ---------------------------------------------------------------------
-- 自检（⚠️ 只打印、不会让迁移失败 —— 真正的硬断言在 MigrationSmokeTest 里）：
--   有输出 = 上面两条参数没写进去。
--   ⛔ 不要在这里假装是门禁：Flyway 只按 SQL 错误判定成败，SELECT 出多少行都不影响。
-- ---------------------------------------------------------------------
SELECT 'CHECK_FAIL: 短信闸门参数缺失或值不合法' AS msg, config_key, config_value
  FROM sys_config
 WHERE config_key IN ('security.sms_global_hour_limit', 'security.sms_verify_max_attempts')
   AND (config_value NOT REGEXP '^[0-9]+$' OR CAST(config_value AS UNSIGNED) = 0);

SELECT 'CHECK_FAIL: 短信闸门参数条数不是 2' AS msg, COUNT(*) AS cnt
  FROM sys_config
 WHERE config_key IN ('security.sms_global_hour_limit', 'security.sms_verify_max_attempts')
HAVING COUNT(*) <> 2;
