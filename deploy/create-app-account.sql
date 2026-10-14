-- =====================================================================
-- 应用最小权限账号（部署脚本，⛔ 不进 Flyway 迁移）
--
-- ⭐ 为什么单独放这里而不是 Flyway：
--   账号名 / 口令 / 来源主机都是【环境相关】的，写死在迁移里无法复用，
--   而且建号需要管理员权限 —— 迁移不该要求超出应用运行所需的权限。
--
-- ⭐ 它解决的是什么问题：
--   审计表 sys_audit_log / sys_data_access_log 与流水表 wallet_txn / credit_log
--   都要求「只增不改不删」。但在此之前应用以 root 连库 ——
--   那是一条【声称了但并不存在的控制】（设计文档 §5.8.4 / S4 / §5.8.6 均有声称）。
--   本文件把它变成真的（账号级）。
--
-- =====================================================================
-- ⛔⛔ 一个必须记住的 MySQL 陷阱（本脚本第一版就栽在这里）：
--
--     GRANT SELECT,INSERT,UPDATE,DELETE ON `task_platform`.* TO 'task_app'@'%';
--     REVOKE UPDATE, DELETE ON `task_platform`.`sys_audit_log` FROM 'task_app'@'%';
--                                                                  ↑
--        ERROR 1147: There is no such grant defined for user 'task_app' on
--                    host '%' on table 'sys_audit_log'
--
--   【库级授权无法用表级 REVOKE 收回】。所以正确做法是
--   **一开始就逐表授权**，而不是先给库级再收回来。
-- =====================================================================

CREATE USER IF NOT EXISTS 'task_app'@'%' IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';

-- ---------------------------------------------------------------------
-- 第 1 步：逐表授权（排除四张"只增"表）
--
-- 因为不能手写 67 条 GRANT，这里用【生成 SQL】的两步法：
-- 先执行下面的 SELECT，把它输出的语句复制出来执行。
-- ---------------------------------------------------------------------
SELECT CONCAT('GRANT SELECT, INSERT, UPDATE, DELETE ON `', table_schema, '`.`', table_name,
              '` TO ''task_app''@''%'';') AS stmt
  FROM information_schema.tables
 WHERE table_schema IN ('task_platform', 'task_platform_test')
   AND table_type = 'BASE TABLE'
   AND table_name NOT IN ('sys_audit_log', 'sys_data_access_log', 'wallet_txn', 'credit_log',
                          'flyway_schema_history')
 ORDER BY table_schema, table_name;

-- ---------------------------------------------------------------------
-- 第 2 步：四张"只增"表 —— 只给 SELECT + INSERT
--   ⛔ 审计日志：留痕的写入路径必须可用，但改与删必须不可用
--   ⛔ wallet_txn / credit_log：资金与信誉流水同理
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT ON `task_platform`.`sys_audit_log`       TO 'task_app'@'%';
GRANT SELECT, INSERT ON `task_platform`.`sys_data_access_log` TO 'task_app'@'%';
GRANT SELECT, INSERT ON `task_platform`.`wallet_txn`          TO 'task_app'@'%';
GRANT SELECT, INSERT ON `task_platform`.`credit_log`          TO 'task_app'@'%';

-- flyway_schema_history：应用启动要跑迁移，需要读写（⛔ 若迁移由独立发布账号执行，
-- 这里可以只给 SELECT）
GRANT SELECT, INSERT, UPDATE, DELETE ON `task_platform`.`flyway_schema_history` TO 'task_app'@'%';

FLUSH PRIVILEGES;

-- =====================================================================
-- 自检（执行后必须做，否则等于没建）：
--
-- ① 看权限清单 —— 四张只增表里【不应出现】UPDATE / DELETE
--      SHOW GRANTS FOR 'task_app'@'%';
--
-- ② 用该账号实测，三条期望结果：
--      mysql -utask_app -p task_platform -e "SELECT COUNT(*) FROM sys_audit_log;"
--        → 成功（审计功能仍需可读）
--      mysql -utask_app -p task_platform -e "DELETE FROM sys_audit_log LIMIT 1;"
--        → ERROR 1142: DELETE command denied to user 'task_app'@...
--      mysql -utask_app -p task_platform -e "DELETE FROM task WHERE id=-1;"
--        → 成功（证明不是把所有表都禁了）
--
-- ⚠️ 让应用真正用上它，还要改配置：
--      PLATFORM_DB_USERNAME=task_app
--      PLATFORM_DB_PASSWORD=<强口令>
--    ⛔ 在此之前（例如当前 application-dev.yml 里的 root/123456），
--      本文件形同虚设 —— 应用仍以 root 连库，UPDATE/DELETE 依然可行。
--      ⭐ 这一点是"控制是否真的生效"的关键，不要只看建了账号就以为完事。
-- =====================================================================
