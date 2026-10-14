-- 只建库，⛔ 不建表 —— 表结构由 Flyway 迁移（db/migration/V1–V6）创建，保证 DDL 单一来源。
-- 幂等：重复执行不报错。

CREATE DATABASE IF NOT EXISTS `task_platform`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS `task_platform_test`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
