-- =====================================================================
-- V7：订正审计表的注释 —— 原先声称的「应用账号无 UPDATE/DELETE 权限」并未实现
--
-- 背景（独立审计实测，2026-10-15）：
--   V1 里 sys_audit_log 的表注释写着「应用账号仅有 INSERT/SELECT（§5.8.4）」，
--   但实测：① 库里只有 root 一个账号，应用【以 root 连库】；
--         ② information_schema.triggers 零触发器；
--         ③ 仓库自带的 HashChainTest 就能 DELETE / UPDATE 该表并成功。
--   ⇒ 也就是说：那是一条【声称了但并不存在的控制】。
--
-- ⛔ 为什么不在 V1 里直接改掉那句话：
--   V1 是已应用的迁移。改它任何一个字符都会改变 Flyway 的 checksum，
--   导致【所有已存在的数据库】在下次启动时报
--   "Migration checksum mismatch" 而起不来。
--   所以历史文件保持原样，用前向迁移订正。
--
-- ⚠️ 本迁移【只订正注释】，不建立账号 —— 建账号需要管理员权限，
--   且账号名/口令/来源主机是【环境相关】的，不该写死在 Flyway 迁移里。
--   对应的部署脚本见 deploy/create-app-account.sql。
-- =====================================================================

ALTER TABLE `sys_audit_log`
    COMMENT = '业务审计日志（只增，应用侧约定）；⚠️ DB 层强制需部署时执行 deploy/create-app-account.sql —— 在此之前应用以 root 连库，UPDATE/DELETE 实际可行';

ALTER TABLE `sys_data_access_log`
    COMMENT = '敏感数据访问审计（谁在何时看了谁的 L3/L4 数据）；导出行为也记这里（§5.8.3）；⚠️ 同 sys_audit_log，DB 层只增强制需部署时建最小权限账号';
