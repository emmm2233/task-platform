-- =====================================================================
--  任务撮合平台 · 数据库基线 DDL  V1
--  模块：身份与权限(identity) + 任务(task)
--  对应设计：docs/2026-10-15-任务撮合平台-总体设计.md  §5.4 §5.14 §5.13 §4.9
--  MySQL 8.0.16+ / InnoDB / utf8mb4
-- ---------------------------------------------------------------------
--  约定（全库统一，后续所有迁移文件遵守）：
--   1. 主键统一 `id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT`
--   2. 时间统一 `created_at` / `updated_at`，DATETIME（不用 TIMESTAMP，避免 2038）
--   3. 金额一律 `DECIMAL(18,2)`，⛔ 绝不用 FLOAT/DOUBLE
--   4. 状态/枚举一律 `VARCHAR(32)` 存**英文大写常量**，⛔ 不用 TINYINT 数字
--      （数字枚举一旦含义变更就是灾难，且日志不可读；字典由应用层 + sys_config 维护）
--   5. ⛔ **不建物理外键**：便于将来分库分表、避免锁升级与级联误删；
--      关联关系写在注释里，由应用层保证（配合集成测试）
--   6. ✅ 建 CHECK 约束（MySQL 8.0.16+）：把"不可能的数据"挡在写入时
--   7. 索引命名 `idx_` / 唯一索引 `uk_` / 全文 `ft_`
--   8. 逻辑删除仅用于**可删的业务对象**（草稿、收藏、模板）；
--      资金、订单、审计、流水类表 ⛔ 永不删除
--   9. 所有表与列必须有 COMMENT（等保与运维可读性要求）
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================
--  0. 全局配置
-- =====================================================================

DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key`    VARCHAR(128)    NOT NULL                COMMENT '配置键，唯一',
  `config_value`  VARCHAR(1024)   NOT NULL                COMMENT '配置值（统一存字符串，应用层转型）',
  `value_type`    VARCHAR(16)     NOT NULL DEFAULT 'STRING' COMMENT 'STRING/INT/DECIMAL/BOOL/JSON',
  `group_code`    VARCHAR(64)     NOT NULL DEFAULT 'DEFAULT' COMMENT '分组：COMMISSION/CREDIT/TASK/SECURITY/WITHDRAW...',
  `description`   VARCHAR(512)    NOT NULL DEFAULT ''     COMMENT '说明（必填，运维要知道它干什么）',
  `editable`      TINYINT         NOT NULL DEFAULT 1      COMMENT '是否允许后台修改',
  `operator_id`   BIGINT UNSIGNED NULL                    COMMENT '最后修改人',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`),
  KEY `idx_group` (`group_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数（费率/门槛/超时天数/SLA 全部走这里，⛔ 禁止硬编码）';


-- =====================================================================
--  1. 用户与权限（identity）
-- =====================================================================

DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_no`           VARCHAR(32)     NOT NULL                COMMENT '对外业务号（U+日期+随机），⛔ 不暴露自增 id',
  `identity`          VARCHAR(16)     NOT NULL                COMMENT '平台身份：USER/OPERATOR/ADMIN —— 安全边界，只有 3 个',
  `phone`             VARCHAR(64)     NOT NULL                COMMENT '手机号密文（信封加密 v1:base64）',
  `phone_hash`        VARCHAR(64)     NOT NULL                COMMENT '手机号盲索引（HMAC），用于等值查询与唯一约束',
  `password_hash`     VARCHAR(128)    NULL                    COMMENT 'BCrypt；内部账号可空（走 SSO/MFA）',
  `nickname`          VARCHAR(64)     NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`        VARCHAR(512)    NOT NULL DEFAULT ''     COMMENT '头像',
  `intro`             VARCHAR(1024)   NOT NULL DEFAULT ''     COMMENT '个人简介',
  `service_region`    VARCHAR(128)    NOT NULL DEFAULT ''     COMMENT '服务区域（线下任务用）',
  `real_name_status`  VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT '实名状态：NONE/PENDING/PASSED/REJECTED',
  `credit_score`      INT             NOT NULL DEFAULT 60     COMMENT '信誉分 0-100，初始 60（§4.9.1）',
  `credit_level`      VARCHAR(16)     NOT NULL DEFAULT 'NORMAL' COMMENT '信誉等级冗余列，供列表排序检索：BLACKLIST/POOR/NORMAL/GOOD/EXCELLENT/CERTIFIED',
  `deposit_amount`    DECIMAL(18,2)   NOT NULL DEFAULT 0.00   COMMENT '已缴保证金',
  `mfa_enabled`       TINYINT         NOT NULL DEFAULT 0      COMMENT '是否启用双因素（内部账号强制 1）',
  `mfa_secret`        VARBINARY(256)  NULL                    COMMENT 'MFA 密钥（加密存储）',
  `status`            VARCHAR(16)     NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/FROZEN/CANCELLED；CANCELLED=已注销',
  `freeze_reason`     VARCHAR(512)    NOT NULL DEFAULT ''     COMMENT '冻结原因',
  `frozen_at`         DATETIME        NULL                    COMMENT '冻结时间',
  `frozen_by`         BIGINT UNSIGNED NULL                    COMMENT '冻结操作人（管理员）',
  `permission_version` INT            NOT NULL DEFAULT 1      COMMENT '权限版本号，变更时 +1 → 强制刷新令牌权限（§5.13.6）',
  `source_client`     VARCHAR(16)     NOT NULL DEFAULT 'web-user' COMMENT '注册来源端，多端归因',
  `last_login_at`     DATETIME        NULL,
  `last_login_ip`     VARCHAR(64)     NOT NULL DEFAULT '',
  `cancelled_at`      DATETIME        NULL                    COMMENT '注销时间（30 天后匿名化）',
  `version`           INT             NOT NULL DEFAULT 0      COMMENT '乐观锁',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_no` (`user_no`),
  UNIQUE KEY `uk_phone_hash` (`phone_hash`),
  KEY `idx_identity_status` (`identity`, `status`),
  KEY `idx_credit_level` (`credit_level`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `ck_user_credit_range` CHECK (`credit_score` BETWEEN 0 AND 100),
  CONSTRAINT `ck_user_identity`     CHECK (`identity` IN ('USER','OPERATOR','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表（含内部账号；平台身份只有 3 个，岗位走 sys_role）';

DROP TABLE IF EXISTS `sys_real_name`;
CREATE TABLE `sys_real_name` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT UNSIGNED NOT NULL              COMMENT '关联 sys_user.id，一人一条有效记录',
  `real_type`       VARCHAR(16)     NOT NULL DEFAULT 'PERSONAL' COMMENT 'PERSONAL 个人 / ENTERPRISE 企业',
  `real_name`       VARCHAR(64)     NOT NULL              COMMENT '真实姓名（L3）',
  `id_card_enc`     VARBINARY(512)  NOT NULL              COMMENT '身份证号密文 AES-256-GCM（L4，信封加密）',
  `id_card_hash`    VARCHAR(64)     NOT NULL              COMMENT '身份证盲索引（防一人多号）',
  `id_card_masked`  VARCHAR(32)     NOT NULL DEFAULT ''   COMMENT '脱敏串 410***********1234（列表展示用，避免解密）',
  `id_card_front`   VARCHAR(512)    NOT NULL DEFAULT ''   COMMENT '证件正面（私有 Bucket key）（L4）',
  `id_card_back`    VARCHAR(512)    NOT NULL DEFAULT ''   COMMENT '证件反面（L4）',
  `face_verify_id`  VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '人脸核身流水号（L4）',
  `enterprise_name` VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '企业名称',
  `license_no`      VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '统一社会信用代码',
  `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/REJECTED',
  `audit_by`        BIGINT UNSIGNED NULL                  COMMENT '审核人（实名审核员岗位）',
  `audit_at`        DATETIME        NULL,
  `reject_reason`   VARCHAR(512)    NOT NULL DEFAULT '',
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status_created` (`status`, `created_at`),
  KEY `idx_id_card_hash` (`id_card_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实名认证记录（L4 数据集中地，访问需审批+水印+审计）';

DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_code`     VARCHAR(64)     NOT NULL                COMMENT '角色代码：ROLE_AUDITOR/ROLE_ARBITER/ROLE_SUPER_ADMIN...',
  `role_name`     VARCHAR(64)     NOT NULL                COMMENT '角色名：内容审核员/仲裁专员...',
  `identity`      VARCHAR(16)     NOT NULL                COMMENT '归属平台身份：USER/OPERATOR/ADMIN',
  `data_scope`    VARCHAR(16)     NOT NULL DEFAULT 'SELF' COMMENT '数据范围：SELF 本人 / GROUP 本组 / ALL 全部',
  `builtin`       TINYINT         NOT NULL DEFAULT 1      COMMENT '是否内置角色（内置不可删）',
  `description`   VARCHAR(512)    NOT NULL DEFAULT '',
  `enabled`       TINYINT         NOT NULL DEFAULT 1,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`),
  CONSTRAINT `ck_role_identity` CHECK (`identity` IN ('USER','OPERATOR','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色（=岗位）表；平台身份 3 个不变，岗位靠这张表细分（§4.7.1、§5.13.2）';

DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `perm_code`   VARCHAR(128)    NOT NULL                COMMENT '权限点：domain:resource:action，如 dispute:verdict:create',
  `perm_name`   VARCHAR(64)     NOT NULL                COMMENT '权限名（中文）',
  `module`      VARCHAR(32)     NOT NULL                COMMENT '所属模块：task/award/order/wallet/dispute/credit/admin',
  `perm_type`   VARCHAR(16)     NOT NULL DEFAULT 'API'  COMMENT 'API/MENU/BUTTON',
  `risk_level`  VARCHAR(16)     NOT NULL DEFAULT 'LOW'  COMMENT '风险级别 LOW/MEDIUM/HIGH；HIGH 需二次认证或复核（§5.8.9）',
  `description` VARCHAR(512)    NOT NULL DEFAULT '',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`perm_code`),
  KEY `idx_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点表；⛔ 必须与代码里的 @PreAuthorize 一一对应，由 CI 校验（§5.13.2）';

DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `role_id`     BIGINT UNSIGNED NOT NULL,
  `granted_by`  BIGINT UNSIGNED NOT NULL              COMMENT '授予人（超管）',
  `reason`      VARCHAR(512)    NOT NULL DEFAULT ''   COMMENT '授予原因（审计要求）',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色绑定；分配时须过互斥校验（sys_role_conflict）';

DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id`       BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
  KEY `idx_perm` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限绑定';

DROP TABLE IF EXISTS `sys_role_conflict`;
CREATE TABLE `sys_role_conflict` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_code_a` VARCHAR(64)     NOT NULL              COMMENT '互斥岗位 A',
  `role_code_b` VARCHAR(64)     NOT NULL              COMMENT '互斥岗位 B（A/B 无序，查询时两个方向都要查）',
  `reason`      VARCHAR(512)    NOT NULL              COMMENT '互斥原因（如"裁决权与资金执行权分离"）',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conflict_pair` (`role_code_a`, `role_code_b`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互斥岗位对（§4.7.5）；⛔ 分配角色时必须校验，绕过即违规';


-- =====================================================================
--  2. 审批与复核（SoD）
-- =====================================================================

DROP TABLE IF EXISTS `approval_task`;
CREATE TABLE `approval_task` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type`            VARCHAR(32)     NOT NULL              COMMENT '业务类型：DISPUTE_VERDICT/WITHDRAW/FUND_ADJUST/CREDIT_ADJUST/COMMISSION_RULE',
  `biz_id`              BIGINT UNSIGNED NOT NULL              COMMENT '业务对象 id',
  `biz_amount`          DECIMAL(18,2)   NULL                  COMMENT '涉及金额（用于按金额定档）',
  `required_role_codes` JSON            NOT NULL              COMMENT '需要的岗位代码数组',
  `required_count`      INT             NOT NULL              COMMENT '需要的票数',
  `initiator_id`        BIGINT UNSIGNED NOT NULL              COMMENT '发起人（⛔ 不得为自己的动作投票）',
  `status`              VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/EXECUTED/EXPIRED',
  `expire_at`           DATETIME        NOT NULL              COMMENT '超时时间（默认 24h，超时升级）',
  `executed_at`         DATETIME        NULL                  COMMENT '业务实际执行时间（幂等标记）',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`),
  KEY `idx_status_expire` (`status`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='复核审批单（双人/三人复核，§4.7.5、§5.13.4）';

DROP TABLE IF EXISTS `approval_record`;
CREATE TABLE `approval_record` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`       BIGINT UNSIGNED NOT NULL              COMMENT '关联 approval_task.id',
  `approver_id`   BIGINT UNSIGNED NOT NULL              COMMENT '投票人',
  `approver_role` VARCHAR(64)     NOT NULL              COMMENT '投票时使用的岗位',
  `decision`      VARCHAR(16)     NOT NULL              COMMENT 'APPROVE/REJECT',
  `comment`       VARCHAR(1024)   NOT NULL DEFAULT ''   COMMENT '意见（驳回时必填）',
  `mfa_verified`  TINYINT         NOT NULL DEFAULT 0    COMMENT '是否通过二次认证（敏感操作必须 1）',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_approver` (`task_id`, `approver_id`),
  KEY `idx_approver` (`approver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='复核投票记录；uk(task_id,approver_id) 从数据库层保证「同一人不得投两票」';


-- =====================================================================
--  3. 审计（只增不改不删，⛔ 应用账号无 UPDATE/DELETE 权限）
-- =====================================================================

DROP TABLE IF EXISTS `sys_audit_log`;
CREATE TABLE `sys_audit_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `operator_id`   BIGINT UNSIGNED NOT NULL              COMMENT '操作人（系统操作填 0）',
  `operator_role` VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '操作时岗位',
  `identity`      VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT '平台身份',
  `action`        VARCHAR(128)    NOT NULL              COMMENT '动作码，如 account:user:freeze',
  `biz_type`      VARCHAR(32)     NOT NULL DEFAULT ''   COMMENT '对象类型',
  `biz_id`        BIGINT UNSIGNED NULL                  COMMENT '对象 id',
  `before_value`  JSON            NULL                  COMMENT '变更前（结构化）',
  `after_value`   JSON            NULL                  COMMENT '变更后（结构化）',
  `reason`        VARCHAR(512)    NOT NULL DEFAULT ''   COMMENT '原因（高风险动作必填）',
  `ip`            VARCHAR(64)     NOT NULL DEFAULT '',
  `user_agent`    VARCHAR(512)    NOT NULL DEFAULT '',
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '链路追踪 id',
  `chain_hour`    CHAR(13)        NOT NULL              COMMENT '⭐ 链分段 YYYY-MM-DD-HH —— 按小时分段建链，避免全局串行（P0-③）',
  `chain_seq`     INT             NOT NULL              COMMENT '⭐ 该小时段内序号 1,2,3…，与 chain_hour 组成唯一键',
  `prev_hash`     CHAR(64)        NOT NULL DEFAULT ''   COMMENT '同段内前一条的 row_hash（段首为 64 个 0）',
  `row_hash`      CHAR(64)        NOT NULL DEFAULT ''   COMMENT '本条记录的哈希',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '可信时间源（NTP），⛔ 不用应用本地时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chain` (`chain_hour`, `chain_seq`),          -- ⭐ 链段内序号唯一，并发下不会写坏链（P0-③）
  KEY `idx_operator_time` (`operator_id`, `created_at`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_action_time` (`action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务审计日志（只增）；独立库存储 + 应用账号仅有 INSERT/SELECT（§5.8.4）';

DROP TABLE IF EXISTS `sys_data_access_log`;
CREATE TABLE `sys_data_access_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `operator_id`  BIGINT UNSIGNED NOT NULL              COMMENT '访问人',
  `target_type`  VARCHAR(32)     NOT NULL              COMMENT '被访问对象类型：REAL_NAME/ID_CARD/PHONE/BANK_CARD/DELIVERY',
  `target_id`    BIGINT UNSIGNED NULL                  COMMENT '被访问对象 id',
  `target_user`  BIGINT UNSIGNED NULL                  COMMENT '数据归属用户',
  `data_level`   VARCHAR(8)      NOT NULL              COMMENT '数据级别 L3/L4',
  `scene`        VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '访问场景：AUDIT/VERDICT/DISPUTE/EXPORT',
  `rows_count`   INT             NOT NULL DEFAULT 1    COMMENT '涉及行数（导出时记录总量）',
  `ip`           VARCHAR(64)     NOT NULL DEFAULT '',
  `trace_id`     VARCHAR(64)     NOT NULL DEFAULT '',
  `chain_hour`   CHAR(13)        NOT NULL              COMMENT '⭐ 链分段（P1-⑩：访问审计同样敏感，与业务审计同规格）',
  `chain_seq`    INT             NOT NULL,
  `prev_hash`    CHAR(64)        NOT NULL DEFAULT '',
  `row_hash`     CHAR(64)        NOT NULL DEFAULT '',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chain` (`chain_hour`, `chain_seq`),
  KEY `idx_operator_time` (`operator_id`, `created_at`),
  KEY `idx_target_user` (`target_user`),
  KEY `idx_level_time` (`data_level`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感数据访问审计（谁在何时看了谁的 L3/L4 数据）；导出行为也记这里（§5.8.3）';

DROP TABLE IF EXISTS `sys_login_log`;
CREATE TABLE `sys_login_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NULL                  COMMENT '登录失败且账号不存在时为空',
  `login_name`  VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '登录标识（手机号脱敏）',
  `result`      VARCHAR(16)     NOT NULL              COMMENT 'SUCCESS/FAIL_PASSWORD/FAIL_CAPTCHA/FAIL_LOCKED/FAIL_MFA',
  `fail_reason` VARCHAR(256)    NOT NULL DEFAULT '',
  `client`      VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT '来源端',
  `ip`          VARCHAR(64)     NOT NULL DEFAULT '',
  `ip_region`   VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT 'IP 归属地（异地登录判定）',
  `device_id`   VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '设备指纹',
  `user_agent`  VARCHAR(512)    NOT NULL DEFAULT '',
  `risk_tag`    VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '风控标记：NEW_DEVICE/REMOTE/BRUTE_FORCE',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `created_at`),
  KEY `idx_ip_time` (`ip`, `created_at`),
  KEY `idx_result_time` (`result`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志（安全审计，留存≥6个月）；用于撞库检测与异地登录识别（§5.8.1）';


-- =====================================================================
--  4. 客户端能力矩阵（多端预留）
-- =====================================================================

DROP TABLE IF EXISTS `sys_client_capability`;
CREATE TABLE `sys_client_capability` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `client_code`    VARCHAR(16)     NOT NULL              COMMENT 'web-user/web-admin/mp-weixin/app-android/app-ios/desktop',
  `capability`     VARCHAR(64)     NOT NULL              COMMENT '能力项：PAY_WECHAT/PAY_ALIPAY/LARGE_UPLOAD/PUSH/SCAN/LOCATION/BIOMETRIC...',
  `supported`      TINYINT         NOT NULL DEFAULT 0    COMMENT '是否支持',
  `extra_config`   JSON            NULL                  COMMENT '附加约束，如 {"maxFileSizeMb":20,"chunked":false}',
  `min_version`    VARCHAR(32)     NOT NULL DEFAULT ''   COMMENT '最低支持版本',
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_client_cap` (`client_code`, `capability`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='端能力矩阵（§5.15.2 的落表）；前端启动时拉取决定 UI 形态，⛔ 业务代码不得 if(client==MP)';


-- =====================================================================
--  5. 任务分类与任务（task）
-- =====================================================================

DROP TABLE IF EXISTS `task_category`;
CREATE TABLE `task_category` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id`           BIGINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '父分类，0=一级',
  `path`                VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '路径 1/12/103，便于按一级分类检索',
  `level`               TINYINT         NOT NULL DEFAULT 1    COMMENT '层级 1/2/3',
  `code`                VARCHAR(64)     NOT NULL              COMMENT '分类代码 DEV/DESIGN/COPYWRITING/ERRAND...',
  `name`                VARCHAR(64)     NOT NULL              COMMENT '分类名',
  `icon`                VARCHAR(256)    NOT NULL DEFAULT '',
  `allow_forms`         VARCHAR(128)    NOT NULL DEFAULT 'ONLINE,OFFLINE,HYBRID' COMMENT '允许的任务形态',
  `allow_pay_modes`     VARCHAR(256)    NOT NULL DEFAULT 'FIXED,BID,BY_QUANTITY,BY_DAY,MILESTONE' COMMENT '允许的计酬方式',
  `commission_rate`     DECIMAL(6,4)    NULL                  COMMENT '分类覆盖费率，NULL=走阶梯/基准（§4.8.4）',
  `require_license`     TINYINT         NOT NULL DEFAULT 0    COMMENT '是否需要资质证明',
  `audit_level`         VARCHAR(16)     NOT NULL DEFAULT 'MACHINE' COMMENT '审核强度 MACHINE 仅机审 / HUMAN 必人审',
  `need_checkin_default` TINYINT        NOT NULL DEFAULT 0    COMMENT '线下任务是否默认需签到',
  `sort_no`             INT             NOT NULL DEFAULT 0,
  `enabled`             TINYINT         NOT NULL DEFAULT 1,
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_parent` (`parent_id`, `sort_no`),
  KEY `idx_path` (`path`),
  CONSTRAINT `ck_category_rate` CHECK (`commission_rate` IS NULL OR `commission_rate` BETWEEN 0 AND 0.20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务分类树（维度一）；承载分类级配置（§4.10.2）';

DROP TABLE IF EXISTS `task`;
CREATE TABLE `task` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no`                VARCHAR(32)     NOT NULL              COMMENT '对外业务号 T+日期+序列',
  `publisher_id`           BIGINT UNSIGNED NOT NULL              COMMENT '发单方 sys_user.id',
  `category_id`            BIGINT UNSIGNED NOT NULL              COMMENT '维度一：业务分类',
  `category_path`          VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '冗余分类路径',

  -- 三维类型
  `task_form`              VARCHAR(16)     NOT NULL              COMMENT '维度二：ONLINE/OFFLINE/HYBRID（发布后不可改）',
  `pay_mode`               VARCHAR(16)     NOT NULL              COMMENT '维度三：FIXED/BID/BY_QUANTITY/BY_DAY/MILESTONE',
  `tags`                   JSON            NULL                  COMMENT '检索标签数组',
  `skills`                 JSON            NULL                  COMMENT '技能要求数组',

  -- 内容
  `title`                  VARCHAR(128)    NOT NULL,
  `description`            MEDIUMTEXT      NOT NULL              COMMENT '富文本描述（服务端白名单过滤后存储）',
  `deliverable_desc`       TEXT            NULL                  COMMENT '交付物说明',
  `deliverable_form`       VARCHAR(16)     NOT NULL DEFAULT 'FILE' COMMENT 'FILE/ONLINE/ONSITE/PHYSICAL',
  `extra_terms`            TEXT            NULL                  COMMENT '补充条款',

  -- 接单方式
  `grant_mode`             VARCHAR(8)      NOT NULL              COMMENT 'GRAB 抢单 / BID 竞拍',
  `award_mode`             VARCHAR(8)      NOT NULL DEFAULT 'SINGLE' COMMENT 'SINGLE 单选 / MULTI 多选（与 MILESTONE 互斥）',
  `required_count`         INT             NOT NULL DEFAULT 1    COMMENT 'MULTI 需要人数',

  -- 金额（按 pay_mode 选用）
  `price`                  DECIMAL(18,2)   NULL                  COMMENT '一口价 FIXED',
  `budget_min`             DECIMAL(18,2)   NULL                  COMMENT '竞价下限 BID',
  `budget_max`             DECIMAL(18,2)   NULL                  COMMENT '竞价上限 BID',
  `unit_price`             DECIMAL(18,2)   NULL                  COMMENT '按量单价 BY_QUANTITY',
  `quantity`               INT             NULL                  COMMENT '按量数量',
  `unit`                   VARCHAR(16)     NULL                  COMMENT '计量单位：篇/条/张/小时',
  `day_rate`               DECIMAL(18,2)   NULL                  COMMENT '按天日薪 BY_DAY',
  `days`                   INT             NULL                  COMMENT '天数',
  `expense_reimburse`      TINYINT         NOT NULL DEFAULT 0    COMMENT '是否报销材料/差旅',
  `expense_cap`            DECIMAL(18,2)   NULL                  COMMENT '报销上限',
  `commission_snapshot`    JSON            NULL                  COMMENT '⭐ 发布时费率快照（费率变更不追溯，§5.12.7）',

  -- 交付与验收
  `allow_partial_delivery` TINYINT         NOT NULL DEFAULT 0    COMMENT '是否允许分批交付',
  `accept_days`            INT             NOT NULL DEFAULT 7    COMMENT '验收期天数（覆盖全局默认）',
  `auto_accept_at`         DATETIME        NULL                  COMMENT '⭐ 自动验收时间点（交付时写入；建索引供定时任务扫，G6）',
  `modify_round_limit`     INT             NOT NULL DEFAULT 3    COMMENT '修改轮次上限（超限自动转争端）',

  -- 时间
  `start_at`               DATETIME        NULL                  COMMENT '期望开始时间',
  `deadline`               DATETIME        NOT NULL              COMMENT '交付截止',
  `bid_deadline`           DATETIME        NULL                  COMMENT '报价截止（BID）',
  `publish_at`             DATETIME        NULL                  COMMENT '实际发布时间',
  `service_time_slots`     JSON            NULL                  COMMENT '线下服务时间段',

  -- 状态
  `status`                 VARCHAR(24)     NOT NULL DEFAULT 'DRAFT' COMMENT '18 状态之一（§4.11.2，字典由 sys_config 维护）',
  `assignee_id`            BIGINT UNSIGNED NULL                  COMMENT '抢单中标人（GRAB 模式）',

  -- 审核
  `audit_status`           VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/PASSED/REJECTED',
  `audit_by`               BIGINT UNSIGNED NULL,
  `audit_at`               DATETIME        NULL,
  `audit_reason`           VARCHAR(512)    NOT NULL DEFAULT '',
  `risk_score`             INT             NOT NULL DEFAULT 0    COMMENT '机审风险分',

  -- 可见性与门槛
  `visibility`             VARCHAR(16)     NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/CERTIFIED_ONLY/INVITE_ONLY',
  `min_credit_level`       VARCHAR(16)     NOT NULL DEFAULT 'NORMAL' COMMENT '接单方信誉门槛（G7）',
  `invited_user_ids`       JSON            NULL                  COMMENT '定向邀请名单',

  -- 合规
  `ip_ownership`           VARCHAR(16)     NOT NULL DEFAULT 'PUBLISHER' COMMENT '知识产权归属 PLATFORM/PUBLISHER/TAKER/SHARED（G9）',
  `confidentiality_required` TINYINT       NOT NULL DEFAULT 0    COMMENT '是否需保密',
  `subcontract_allowed`    TINYINT         NOT NULL DEFAULT 0    COMMENT '是否允许转包，默认 false（G14）',

  -- 冗余统计（避免列表页 N+1，G12）
  `bid_count`              INT             NOT NULL DEFAULT 0,
  `view_count`             INT             NOT NULL DEFAULT 0,
  `escrow_amount`          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
  `escrow_status`          VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/FROZEN/PARTIAL_RELEASED/RELEASED/REFUNDED',

  -- 生命周期留痕
  `award_at`               DATETIME        NULL,
  `cancelled_at`           DATETIME        NULL,
  `cancel_by`              BIGINT UNSIGNED NULL,
  `cancel_reason`          VARCHAR(512)    NOT NULL DEFAULT '',
  `close_reason`           VARCHAR(512)    NOT NULL DEFAULT '',

  -- 模板与归因
  `template_id`            BIGINT UNSIGNED NULL                  COMMENT '来源模板（G13）',
  `parent_task_id`         BIGINT UNSIGNED NULL                  COMMENT '续单来源任务',
  `repeat_rule`            VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '周期规则（Phase 2）',
  `source_client`          VARCHAR(16)     NOT NULL DEFAULT 'web-user' COMMENT '发单来源端（G15）',

  `version`                INT             NOT NULL DEFAULT 0    COMMENT '乐观锁',
  `created_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_hall`            (`status`, `category_id`, `created_at`),          -- 任务大厅默认列表（按最新）
  KEY `idx_hall_amount`     (`status`, `pay_mode`, `price`, `created_at`),   -- ⭐ P2-⑬ 按金额排序走索引，避免 filesort
  KEY `idx_hall_credit`     (`status`, `min_credit_level`, `created_at`),    -- ⭐ P2-⑬ 按信誉门槛排序
  -- ⚠️ P2-⑬ 说明：按「距离」排序无法靠 MySQL 索引（haversine 计算列）→ 先用 task_location.idx_geo 按 city 粗筛，
  --           再由应用层算真 km 排序；Phase 2 上 Elasticsearch geo_distance 后彻底解决
  KEY `idx_grab`            (`status`, `grant_mode`, `deadline`),             -- 抢单截止扫描
  KEY `idx_bid_deadline`    (`status`, `bid_deadline`),                       -- 竞价截止扫描
  KEY `idx_auto_accept`     (`status`, `auto_accept_at`),                     -- ⭐ 自动验收扫描（G6）
  KEY `idx_publisher`       (`publisher_id`, `status`),                       -- 我的发布
  KEY `idx_assignee`        (`assignee_id`, `status`),                        -- 我接的单（GRAB）
  KEY `idx_audit`           (`audit_status`, `created_at`),                   -- 审核队列
  CONSTRAINT `ck_task_form`      CHECK (`task_form` IN ('ONLINE','OFFLINE','HYBRID')),
  CONSTRAINT `ck_task_pay_mode`  CHECK (`pay_mode` IN ('FIXED','BID','BY_QUANTITY','BY_DAY','MILESTONE')),
  CONSTRAINT `ck_task_grant`     CHECK (`grant_mode` IN ('GRAB','BID')),
  CONSTRAINT `ck_task_award`     CHECK (`award_mode` IN ('SINGLE','MULTI')),
  CONSTRAINT `ck_task_deliver`   CHECK (`deliverable_form` IN ('FILE','ONLINE','ONSITE','PHYSICAL')),
  CONSTRAINT `ck_task_visibility` CHECK (`visibility` IN ('PUBLIC','CERTIFIED_ONLY','INVITE_ONLY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务主表（三维类型 + 18 状态 + 缺口补全 G1-G15，§5.14.2）';

DROP TABLE IF EXISTS `task_acceptance_item`;
CREATE TABLE `task_acceptance_item` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`    BIGINT UNSIGNED NOT NULL,
  `seq`        INT             NOT NULL              COMMENT '序号',
  `item_name`  VARCHAR(128)    NOT NULL              COMMENT '验收项',
  `item_desc`  VARCHAR(512)    NOT NULL DEFAULT ''   COMMENT '验收说明',
  `required`   TINYINT         NOT NULL DEFAULT 1    COMMENT '是否必过项',
  `weight`     INT             NOT NULL DEFAULT 0    COMMENT '权重（用于部分验收评分）',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_seq` (`task_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='⭐ 结构化验收标准（G1）——没有它，恶意驳回就无法裁决，争端流程建在沙子上';

DROP TABLE IF EXISTS `task_location`;
CREATE TABLE `task_location` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`           BIGINT UNSIGNED NOT NULL,
  `province`          VARCHAR(32)     NOT NULL DEFAULT '',
  `city`              VARCHAR(32)     NOT NULL DEFAULT '',
  `district`          VARCHAR(32)     NOT NULL DEFAULT '',
  `address`           VARCHAR(256)    NOT NULL DEFAULT ''   COMMENT '详细地址（L3）',
  `lng`               DECIMAL(10,6)   NOT NULL              COMMENT '经度（用于真 km 距离计算）',
  `lat`               DECIMAL(10,6)   NOT NULL              COMMENT '纬度',
  `service_radius_km` INT             NOT NULL DEFAULT 0    COMMENT '服务半径，0=不限',
  `need_checkin`      TINYINT         NOT NULL DEFAULT 0    COMMENT '是否需要到场签到',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task` (`task_id`),
  KEY `idx_geo` (`city`, `district`),
  KEY `idx_lng_lat` (`lng`, `lat`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='线下任务地点（G5）——task_form=OFFLINE/HYBRID 时必填';

DROP TABLE IF EXISTS `task_milestone`;
CREATE TABLE `task_milestone` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`          BIGINT UNSIGNED NOT NULL,
  `seq`              INT             NOT NULL              COMMENT '阶段序号',
  `name`             VARCHAR(128)    NOT NULL              COMMENT '阶段名',
  `amount`           DECIMAL(18,2)   NOT NULL              COMMENT '该阶段金额（Σ 须等于总预算）',
  `prepay_ratio`     DECIMAL(6,4)    NOT NULL DEFAULT 0.00 COMMENT '预付比例（Q22，默认 0）',
  `deliverable_desc` VARCHAR(1024)   NOT NULL DEFAULT '',
  `deadline`         DATETIME        NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_seq` (`task_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='里程碑计划（pay_mode=MILESTONE）；Phase 1 与 award_mode=MULTI 互斥（G2）';

DROP TABLE IF EXISTS `task_tag`;
CREATE TABLE `task_tag` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`    BIGINT UNSIGNED NOT NULL,
  `tag_type`   VARCHAR(16)     NOT NULL              COMMENT 'TAG 标签 / SKILL 技能',
  `tag_value`  VARCHAR(64)     NOT NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_tag` (`task_id`, `tag_type`, `tag_value`),
  KEY `idx_value` (`tag_type`, `tag_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务标签与技能（G8）；Phase 2 同步进 ES 做检索';

DROP TABLE IF EXISTS `task_attachment`;
CREATE TABLE `task_attachment` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`       BIGINT UNSIGNED NOT NULL,
  `file_name`     VARCHAR(256)    NOT NULL              COMMENT '原始文件名（仅展示，⛔ 不用于落盘路径）',
  `file_key`      VARCHAR(512)    NOT NULL              COMMENT '对象存储 key（UUID 命名）',
  `file_size`     BIGINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '字节',
  `content_type`  VARCHAR(128)    NOT NULL DEFAULT '',
  `sha256`        CHAR(64)        NOT NULL DEFAULT ''   COMMENT '文件哈希（秒传与完整性校验）',
  `scan_status`   VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '病毒扫描 PENDING/CLEAN/INFECTED',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task` (`task_id`),
  KEY `idx_sha256` (`sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务附件（发布时上传）';

DROP TABLE IF EXISTS `task_status_log`;
CREATE TABLE `task_status_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`       BIGINT UNSIGNED NOT NULL,
  `from_status`   VARCHAR(24)     NOT NULL,
  `to_status`     VARCHAR(24)     NOT NULL,
  `from_group`    VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT '状态分组：DRAFT/RECRUIT/PERFORM/CLOSE/TERMINAL',
  `to_group`      VARCHAR(16)     NOT NULL DEFAULT '',
  `event`         VARCHAR(64)     NOT NULL              COMMENT '触发事件',
  `operator_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '0=系统',
  `operator_type` VARCHAR(16)     NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM/USER/OPERATOR/ADMIN',
  `reason`        VARCHAR(512)    NOT NULL DEFAULT '',
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_task_time` (`task_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务状态迁移留痕（审计 + 状态时间轴展示，UC-U-96）';

DROP TABLE IF EXISTS `task_favorite`;
CREATE TABLE `task_favorite` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`    BIGINT UNSIGNED NOT NULL,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `deleted`    TINYINT         NOT NULL DEFAULT 0,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_task` (`user_id`, `task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务收藏（允许逻辑删除）';

DROP TABLE IF EXISTS `task_template`;
CREATE TABLE `task_template` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT UNSIGNED NOT NULL              COMMENT '归属用户',
  `name`          VARCHAR(128)    NOT NULL,
  `snapshot`      JSON            NOT NULL              COMMENT '任务字段快照（发单时回填）',
  `use_count`     INT             NOT NULL DEFAULT 0,
  `deleted`       TINYINT         NOT NULL DEFAULT 0,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务模板（G13，UC-U-99）';
-- =====================================================================
--  6. 运维与安全补充表（P1/P2 缺陷修复）
-- =====================================================================

-- P1-⑪ MFA 恢复码：手机丢失时不至于把内部账号彻底锁死
DROP TABLE IF EXISTS `sys_mfa_recovery_code`;
CREATE TABLE `sys_mfa_recovery_code` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `code_hash`  CHAR(64)        NOT NULL              COMMENT '恢复码的 SHA256（⛔ 不存明文）',
  `seq`        INT             NOT NULL              COMMENT '第几个（1-10）',
  `used`       TINYINT         NOT NULL DEFAULT 0,
  `used_at`    DATETIME        NULL,
  `used_ip`    VARCHAR(64)     NOT NULL DEFAULT '',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_seq` (`user_id`, `seq`),
  KEY `idx_user_used` (`user_id`, `used`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MFA 一次性恢复码（10 个，用后作废）；启用 MFA 时强制生成并要求线下保存';

-- P1-⑫ 访问白名单：管理后台与支付回调的 IP 白名单
DROP TABLE IF EXISTS `sys_access_whitelist`;
CREATE TABLE `sys_access_whitelist` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `scope`       VARCHAR(24)     NOT NULL              COMMENT '管控范围：ADMIN_CONSOLE 管理后台 / PAY_CALLBACK 支付回调 / OPEN_API 开放接口',
  `list_type`   VARCHAR(16)     NOT NULL              COMMENT 'CIDR / IP / VPN_SEGMENT',
  `list_value`  VARCHAR(128)    NOT NULL              COMMENT '如 10.0.0.0/8 或 203.0.113.10',
  `remark`      VARCHAR(256)    NOT NULL DEFAULT '',
  `enabled`     TINYINT         NOT NULL DEFAULT 1,
  `operator_id` BIGINT UNSIGNED NULL,
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_value` (`scope`, `list_value`),
  KEY `idx_scope` (`scope`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访问白名单（P1-⑫）；支付回调另配渠道 IP 白名单（§5.8.11）';

-- P2-⑮ 系统参数变更历史：改了费率/门槛，旧值必须可追溯
DROP TABLE IF EXISTS `sys_config_history`;
CREATE TABLE `sys_config_history` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key`   VARCHAR(128)    NOT NULL,
  `old_value`    VARCHAR(1024)   NOT NULL DEFAULT '',
  `new_value`    VARCHAR(1024)   NOT NULL DEFAULT '',
  `operator_id`  BIGINT UNSIGNED NOT NULL,
  `reason`       VARCHAR(512)    NOT NULL DEFAULT ''   COMMENT '变更原因（费率类必填）',
  `approval_id`  BIGINT UNSIGNED NULL                  COMMENT '双人审批单（费率变更必需）',
  `effective_at` DATETIME        NULL                  COMMENT '预约生效时间',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_key_time` (`config_key`, `created_at`),
  KEY `idx_operator` (`operator_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数变更历史（P2-⑮，只增）；费率/门槛类变更必须能回答"当时是多少、谁改的、为什么"';

-- P2-⑭-a 限流规则落表（原只写在文档里）
DROP TABLE IF EXISTS `sys_rate_limit_rule`;
CREATE TABLE `sys_rate_limit_rule` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `rule_code`      VARCHAR(64)     NOT NULL,
  `resource`       VARCHAR(128)    NOT NULL              COMMENT '接口路径或资源标识，支持通配',
  `dimension`      VARCHAR(16)     NOT NULL              COMMENT 'IP / USER / DEVICE / GLOBAL / CHANNEL',
  `threshold`      INT             NOT NULL              COMMENT '窗口内允许次数',
  `window_seconds` INT             NOT NULL DEFAULT 60,
  `action`         VARCHAR(16)     NOT NULL DEFAULT 'REJECT' COMMENT 'REJECT 拒绝 / QUEUE 排队 / DEGRADE 降级 / CAPTCHA 挑战',
  `enabled`        TINYINT         NOT NULL DEFAULT 1,
  `remark`         VARCHAR(256)    NOT NULL DEFAULT '',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_code` (`rule_code`),
  KEY `idx_resource` (`resource`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限流规则（P2-⑭）；落表后可热更新 + 审计，⛔ 不再写死在代码或文档里';

-- P2-⑭-b 特性开关（灰度与应急下线）
DROP TABLE IF EXISTS `sys_feature_flag`;
CREATE TABLE `sys_feature_flag` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `flag_key`     VARCHAR(64)     NOT NULL,
  `flag_value`   VARCHAR(256)    NOT NULL              COMMENT 'BOOLEAN / 百分比 / JSON 灰度规则',
  `value_type`   VARCHAR(16)     NOT NULL DEFAULT 'BOOL',
  `description`  VARCHAR(512)    NOT NULL DEFAULT '',
  `emergency`    TINYINT         NOT NULL DEFAULT 0    COMMENT '是否应急开关（如"冻结全部提现""停分账"）',
  `operator_id`  BIGINT UNSIGNED NULL,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_flag_key` (`flag_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='特性开关（P2-⑭）；⭐ 应急开关（冻结提现/停分账/全站下线）必须能一键切换（§5.8.16 R26 对策）';

-- P2-⑯ MQ 事件注册表：统一事件与 Topic 口径
DROP TABLE IF EXISTS `sys_event_registry`;
CREATE TABLE `sys_event_registry` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `topic`             VARCHAR(64)     NOT NULL              COMMENT 'Topic：domain-event / notify / pay-callback',
  `event_code`        VARCHAR(64)     NOT NULL              COMMENT '事件码，如 TASK_AWARDED',
  `event_name`        VARCHAR(128)    NOT NULL,
  `producer`          VARCHAR(32)     NOT NULL              COMMENT '生产模块',
  `consumers`         JSON            NOT NULL              COMMENT '消费模块数组',
  `ordered`           TINYINT         NOT NULL DEFAULT 0    COMMENT '是否要求顺序消费（按业务键）',
  `msg_type`          VARCHAR(16)     NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / TRANSACTION 事务消息 / DELAY 延时消息',
  `payload_schema`    JSON            NULL                  COMMENT '消息体字段说明',
  `idempotent_hint`   VARCHAR(256)    NOT NULL DEFAULT ''   COMMENT '消费幂等建议键',
  `enabled`           TINYINT         NOT NULL DEFAULT 1,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_code` (`event_code`),
  KEY `idx_topic` (`topic`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='⭐ MQ 事件与 Topic 注册表（P2-⑯）；缓存刷新广播、领域事件、延时消息的口径全部以此为准';


SET FOREIGN_KEY_CHECKS = 1;
