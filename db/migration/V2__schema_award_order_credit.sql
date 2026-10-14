-- =====================================================================
--  任务撮合平台 · 数据库基线 DDL  V2
--  模块：接单与竞拍(award) + 订单履约(order) + 信誉分(credit)
--  对应设计：§5.5.1 抢单原子性 / §5.5.2 竞拍与 MULTI / §5.5.3 拆单
--            §4.11 状态机 / §5.14.3 delivery_unit / §4.9 §5.11 信誉分
-- =====================================================================

SET NAMES utf8mb4;

-- =====================================================================
--  1. 抢单（award）
-- =====================================================================

DROP TABLE IF EXISTS `task_grab_log`;
CREATE TABLE `task_grab_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`      BIGINT UNSIGNED NOT NULL,
  `user_id`      BIGINT UNSIGNED NOT NULL              COMMENT '抢单人',
  `request_id`   VARCHAR(64)     NOT NULL              COMMENT '客户端幂等键 Idempotency-Key',
  `success`      TINYINT         NOT NULL              COMMENT '是否抢到',
  `fail_code`    VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT '失败码 31001/31002/31003/31004...',
  `fail_reason`  VARCHAR(256)    NOT NULL DEFAULT ''   COMMENT '失败原因（用于产品分析与风控）',
  `task_status_at_fail` VARCHAR(24) NOT NULL DEFAULT '' COMMENT '失败时任务状态（回读所得）',
  `device_id`    VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '设备指纹（脚本抢单识别）',
  `ip`           VARCHAR(64)     NOT NULL DEFAULT '',
  `cost_ms`      INT             NOT NULL DEFAULT 0    COMMENT '接口耗时，用于 P99 观测',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`task_id`, `user_id`, `request_id`),   -- ⭐ 抢单幂等（§5.9.1）
  KEY `idx_user_time` (`user_id`, `created_at`),
  KEY `idx_task_success` (`task_id`, `success`),               -- 一单成功记录数必须恒为 1
  KEY `idx_fail` (`fail_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢单尝试日志；⭐ 成功记录数恒为 1 是并发正确性的自证（§5.5.1）';

DROP TABLE IF EXISTS `task_bid`;
CREATE TABLE `task_bid` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`        BIGINT UNSIGNED NOT NULL,
  `bidder_id`      BIGINT UNSIGNED NOT NULL              COMMENT '报价人',
  `quote_amount`   DECIMAL(18,2)   NOT NULL              COMMENT '报价金额',
  `duration_days`  INT             NOT NULL              COMMENT '承诺工期（天）',
  `plan_desc`      TEXT            NULL                  COMMENT '方案说明',
  `attachments`    JSON            NULL                  COMMENT '方案附件 key 数组',
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'QUOTED' COMMENT 'QUOTED/SHORTLISTED/ACCEPTED/REJECTED/WITHDRAWN',
  `abnormal_flag`  VARCHAR(32)     NOT NULL DEFAULT ''   COMMENT '异常标记 LOW_PRICE（低于预算下限50%）等',
  `bidder_level`   VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT '报价时的信誉等级快照（用于排序与仲裁取证）',
  `version`        INT             NOT NULL DEFAULT 0    COMMENT '修改次数（配合 history）',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_bidder` (`task_id`, `bidder_id`),        -- 一人一份报价（可覆盖更新）
  KEY `idx_task_status_amount` (`task_id`, `status`, `quote_amount`),  -- 报价列表排序
  KEY `idx_bidder` (`bidder_id`, `status`),                    -- 我的报价
  CONSTRAINT `ck_bid_status` CHECK (`status` IN ('QUOTED','SHORTLISTED','ACCEPTED','REJECTED','WITHDRAWN')),
  CONSTRAINT `ck_bid_amount` CHECK (`quote_amount` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='竞拍报价（暗标：互相不可见）';

DROP TABLE IF EXISTS `task_bid_history`;
CREATE TABLE `task_bid_history` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bid_id`        BIGINT UNSIGNED NOT NULL,
  `version`       INT             NOT NULL              COMMENT '第几版',
  `quote_amount`  DECIMAL(18,2)   NOT NULL,
  `duration_days` INT             NOT NULL,
  `plan_desc`     TEXT            NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bid_version` (`bid_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价修改历史；⭐ 争议时"我当时报的是多少"的核心证据，⛔ 不可删';

DROP TABLE IF EXISTS `award`;
CREATE TABLE `award` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`         BIGINT UNSIGNED NOT NULL,
  `bid_id`          BIGINT UNSIGNED NULL                COMMENT 'GRAB 模式为空',
  `awardee_id`      BIGINT UNSIGNED NOT NULL            COMMENT '中标人',
  `awarded_amount`  DECIMAL(18,2)   NOT NULL            COMMENT '该中标人份额',
  `platform_fee`    DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '该份额预估佣金（结算时以 commission_detail 为准）',
  `award_mode`      VARCHAR(8)      NOT NULL            COMMENT 'SINGLE/MULTI',
  `grant_mode`      VARCHAR(8)      NOT NULL            COMMENT 'GRAB/BID',
  `awarded_by`      BIGINT UNSIGNED NOT NULL            COMMENT '发单方',
  `idempotent_key`  VARCHAR(128)    NOT NULL            COMMENT '⭐ 幂等键：taskId+awardMode+排序后 bidIds 哈希',
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),                     -- ⭐ 中标幂等（§5.9.1）
  KEY `idx_task` (`task_id`),
  KEY `idx_awardee` (`awardee_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中标记录（MULTI 下一任务 N 条）';


-- =====================================================================
--  2. 订单与履约（order）
-- =====================================================================

DROP TABLE IF EXISTS `biz_order`;
CREATE TABLE `biz_order` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no`         VARCHAR(32)     NOT NULL              COMMENT '对外订单号 O+日期+序列',
  `task_id`          BIGINT UNSIGNED NOT NULL,
  `publisher_id`     BIGINT UNSIGNED NOT NULL,
  `total_amount`     DECIMAL(18,2)   NOT NULL              COMMENT '托管总额（Σ 子订单金额）',
  `platform_fee`     DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '佣金汇总（仅报表用，⛔ 不作计算依据）',
  `sub_order_count`  INT             NOT NULL DEFAULT 1,
  `settled_count`    INT             NOT NULL DEFAULT 0    COMMENT '已结算子订单数（主状态聚合用）',
  `status`           VARCHAR(24)     NOT NULL DEFAULT 'PENDING_START' COMMENT '聚合状态（由子订单推导，§4.11.6）',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_task` (`task_id`),                            -- 一个任务一个主订单
  KEY `idx_publisher_status` (`publisher_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主订单；钱在主订单上（用户只付一次），履约粒度在子订单/delivery_unit';

DROP TABLE IF EXISTS `sub_order`;
CREATE TABLE `sub_order` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sub_order_no`    VARCHAR(32)     NOT NULL,
  `order_id`        BIGINT UNSIGNED NOT NULL,
  `task_id`         BIGINT UNSIGNED NOT NULL              COMMENT '冗余，便于直接按任务查',
  `taker_id`        BIGINT UNSIGNED NOT NULL              COMMENT '接单方',
  `award_id`        BIGINT UNSIGNED NOT NULL,
  `amount`          DECIMAL(18,2)   NOT NULL              COMMENT '该接单方份额（计费基数）',
  `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING_ESCROW' COMMENT 'PENDING_ESCROW/IN_PROGRESS/SUBMITTED/ACCEPTED/REVISION/OVERDUE/DISPUTED/SETTLED/REFUNDED/PARTIAL_SETTLED/CANCELLED',
  `deadline`        DATETIME        NOT NULL              COMMENT '独立交付截止（MULTI 下互不牵连）',
  `accept_deadline` DATETIME        NULL                  COMMENT '验收截止（提交交付后写入）',
  `auto_accept_at`  DATETIME        NULL                  COMMENT '⭐ 自动验收时间点（建索引供扫描）',
  `modify_round`    INT             NOT NULL DEFAULT 0    COMMENT '已用修改轮次',
  `delivered_at`    DATETIME        NULL,
  `accepted_at`     DATETIME        NULL,
  `settled_at`      DATETIME        NULL,
  `dispute_frozen`  TINYINT         NOT NULL DEFAULT 0    COMMENT '⭐ 该份额是否被争端冻结（禁止分账，§5.12.4）',
  `version`         INT             NOT NULL DEFAULT 0,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sub_order_no` (`sub_order_no`),
  KEY `idx_taker_status` (`taker_id`, `status`),                -- 我的接单
  KEY `idx_auto_accept`  (`status`, `auto_accept_at`),          -- 自动验收扫描
  KEY `idx_overdue`      (`status`, `deadline`),                -- 逾期扫描
  KEY `idx_order`        (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='子订单（人维度拆分）：每个中标人一份，独立交付/验收/结算/争端';

DROP TABLE IF EXISTS `delivery_unit`;
CREATE TABLE `delivery_unit` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `unit_no`          VARCHAR(32)     NOT NULL              COMMENT '单元号',
  `sub_order_id`     BIGINT UNSIGNED NOT NULL,
  `order_id`         BIGINT UNSIGNED NOT NULL              COMMENT '冗余，便于按主订单汇总',
  `unit_type`        VARCHAR(16)     NOT NULL              COMMENT 'WHOLE 整体 / MILESTONE 里程碑 / BATCH 批次',
  `seq`              INT             NOT NULL DEFAULT 1    COMMENT '序号',
  `name`             VARCHAR(128)    NOT NULL DEFAULT ''   COMMENT '单元名（里程碑名/批次名）',
  `amount`           DECIMAL(18,2)   NOT NULL              COMMENT '⭐ 该单元金额 —— 资金动作与抽成的最小粒度',
  `status`           VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/IN_PROGRESS/SUBMITTED/ACCEPTED/REJECTED/DISPUTED/SETTLED/REFUNDED',
  `deliverable_desc` VARCHAR(1024)   NOT NULL DEFAULT '',
  `deadline`         DATETIME        NULL,
  `accept_deadline`  DATETIME        NULL,
  `auto_accept_at`   DATETIME        NULL,
  `submitted_at`     DATETIME        NULL,
  `accepted_at`      DATETIME        NULL,
  `settled_at`       DATETIME        NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_unit_no` (`unit_no`),
  KEY `idx_sub_order` (`sub_order_id`, `seq`),
  KEY `idx_order` (`order_id`),
  KEY `idx_scan` (`status`, `auto_accept_at`),
  CONSTRAINT `ck_unit_type` CHECK (`unit_type` IN ('WHOLE','MILESTONE','BATCH')),
  CONSTRAINT `ck_unit_amount` CHECK (`amount` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='⭐ 统一结算单元（G2）：普通任务=1个WHOLE；里程碑=N个；MULTI=各1个。Phase 1 即建此表，Phase 2 放开"里程碑×多人"时⛔ 不必改结算链路';

DROP TABLE IF EXISTS `sub_order_delivery`;
CREATE TABLE `sub_order_delivery` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sub_order_id`  BIGINT UNSIGNED NOT NULL,
  `unit_id`       BIGINT UNSIGNED NULL                  COMMENT '里程碑/批次交付时指向 delivery_unit',
  `round`         INT             NOT NULL DEFAULT 1    COMMENT '第几轮交付',
  `delivery_desc` TEXT            NULL                  COMMENT '交付说明',
  `attachments`   JSON            NULL                  COMMENT '交付物 key 数组',
  `self_check`    JSON            NULL                  COMMENT '⭐ 接单方对照验收标准的自查结果',
  `request_id`    VARCHAR(64)     NOT NULL              COMMENT '幂等键',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`sub_order_id`, `request_id`),         -- 交付幂等（§5.9.1）
  KEY `idx_sub_order_round` (`sub_order_id`, `round`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交付记录（可多轮）；⛔ 不可删，是验收与争端的核心证据';

DROP TABLE IF EXISTS `task_checkin`;
CREATE TABLE `task_checkin` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`       BIGINT UNSIGNED NOT NULL,
  `sub_order_id`  BIGINT UNSIGNED NOT NULL,
  `user_id`       BIGINT UNSIGNED NOT NULL,
  `lng`           DECIMAL(10,6)   NOT NULL,
  `lat`           DECIMAL(10,6)   NOT NULL,
  `address`       VARCHAR(256)    NOT NULL DEFAULT ''   COMMENT '签到地址（逆地理）',
  `distance_m`    INT             NOT NULL DEFAULT 0    COMMENT '与任务地点距离（米），超阈值标记异常',
  `photo_key`     VARCHAR(512)    NOT NULL DEFAULT ''   COMMENT '现场照片',
  `abnormal`      TINYINT         NOT NULL DEFAULT 0    COMMENT '是否异常（距离过远/虚拟定位）',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_user` (`task_id`, `user_id`),
  KEY `idx_sub_order` (`sub_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='线下任务签到（UC-U-97）；可触发 PENDING_START → IN_PROGRESS';

DROP TABLE IF EXISTS `review`;
CREATE TABLE `review` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id`       BIGINT UNSIGNED NOT NULL,
  `sub_order_id`   BIGINT UNSIGNED NOT NULL,
  `from_user_id`   BIGINT UNSIGNED NOT NULL,
  `to_user_id`     BIGINT UNSIGNED NOT NULL,
  `direction`      VARCHAR(16)     NOT NULL              COMMENT 'PUBLISHER_TO_TAKER / TAKER_TO_PUBLISHER',
  `score`          TINYINT         NOT NULL              COMMENT '1-5 星',
  `tags`           JSON            NULL                  COMMENT '标签数组',
  `content`        VARCHAR(1024)   NOT NULL DEFAULT ''   COMMENT '评价内容',
  `anonymous`      TINYINT         NOT NULL DEFAULT 0,
  `visible`        TINYINT         NOT NULL DEFAULT 0    COMMENT '是否已公开（双方互评完成或超时后置 1）',
  `auto_generated` TINYINT         NOT NULL DEFAULT 0    COMMENT '是否超时默认好评',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sub_from` (`sub_order_id`, `from_user_id`),   -- 一人对一单只能评一次
  KEY `idx_to_user` (`to_user_id`, `created_at`),
  CONSTRAINT `ck_review_score` CHECK (`score` BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双向评价；只有真实成交的互评才计入信誉分（§4.9.6）';


-- =====================================================================
--  3. 信誉分（credit）
-- =====================================================================

DROP TABLE IF EXISTS `credit_level`;
CREATE TABLE `credit_level` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `level_code`         VARCHAR(16)     NOT NULL              COMMENT 'BLACKLIST/POOR/NORMAL/GOOD/EXCELLENT/CERTIFIED',
  `level_name`         VARCHAR(32)     NOT NULL              COMMENT '黑名单/较差/一般/良好/优秀/平台认证',
  `min_score`          INT             NOT NULL,
  `max_score`          INT             NOT NULL,
  `badge`              VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT '标识 emoji',
  `max_ongoing_tasks`  INT             NOT NULL DEFAULT 0    COMMENT '进行中任务上限',
  `grab_threshold`     INT             NOT NULL DEFAULT 0    COMMENT '抢单门槛分',
  `min_task_amount`    DECIMAL(18,2)   NULL                  COMMENT '可接金额下限（NULL=不限）',
  `max_task_amount`    DECIMAL(18,2)   NULL                  COMMENT '可接金额上限',
  `sort_weight`        DECIMAL(6,2)    NOT NULL DEFAULT 1.00 COMMENT '排序权重',
  `commission_delta`   DECIMAL(6,4)    NOT NULL DEFAULT 0.00 COMMENT '⭐ 抽成费率修正（小数，如 -0.01 = -1个百分点）',
  `withdraw_days`      INT             NOT NULL DEFAULT 3    COMMENT '⭐ 提现周期 T+N',
  `require_deposit`    TINYINT         NOT NULL DEFAULT 0    COMMENT '是否需缴保证金',
  `support_priority`   INT             NOT NULL DEFAULT 100  COMMENT '平台介入优先级（越小越优先）',
  `publish_weight`     DECIMAL(6,2)    NOT NULL DEFAULT 1.00 COMMENT '发单方侧：任务曝光权重',
  `force_escrow`       TINYINT         NOT NULL DEFAULT 0    COMMENT '发单方侧：是否强制"发布即托管"',
  `max_publish_amount` DECIMAL(18,2)   NULL                  COMMENT '发单方侧：可发布金额上限',
  `award_mode`         VARCHAR(8)      NOT NULL DEFAULT 'AUTO' COMMENT '⭐ 等级获取方式：AUTO 按分数自动判定 / MANUAL 人工授予（平台认证不属于分数档，否则会与「优秀」重叠）',
  `enabled`            TINYINT         NOT NULL DEFAULT 1,
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_level_code` (`level_code`),
  CONSTRAINT `ck_level_range` CHECK (`min_score` <= `max_score` AND `min_score` >= 0 AND `max_score` <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信誉等级与权益矩阵（§4.9.2 落表）；数据量极小 → 本地缓存 Caffeine';

DROP TABLE IF EXISTS `credit_rule`;
CREATE TABLE `credit_rule` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_type`     VARCHAR(48)     NOT NULL              COMMENT '事件类型：FINISH_5STAR/OVERDUE/VERDICT_LOSE/APPEAL_OK/ACCEPT_TIMEOUT...',
  `event_name`     VARCHAR(64)     NOT NULL              COMMENT '事件中文名',
  `role_side`      VARCHAR(16)     NOT NULL              COMMENT 'PUBLISHER 发单方 / TAKER 接单方 / BOTH',
  `delta`          INT             NOT NULL              COMMENT '分值变动（可正可负）',
  `daily_cap`      INT             NOT NULL DEFAULT 0    COMMENT '单日加分上限，0=不限（加分侧用）',
  `decayable`      TINYINT         NOT NULL DEFAULT 1    COMMENT '是否随时间衰减（⭐ 惩罚性扣分必须 0）',
  `description`    VARCHAR(512)    NOT NULL DEFAULT '',
  `enabled`        TINYINT         NOT NULL DEFAULT 1,
  `effective_from` DATETIME        NULL,
  `effective_to`   DATETIME        NULL,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event` (`event_type`),
  CONSTRAINT `ck_rule_side` CHECK (`role_side` IN ('PUBLISHER','TAKER','BOTH')),
  CONSTRAINT `ck_rule_delta` CHECK (`delta` BETWEEN -100 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信誉分计分规则（§4.9.3/§4.9.4 落表）；⛔ 分值禁止硬编码，运营可调';

DROP TABLE IF EXISTS `credit_log`;
CREATE TABLE `credit_log` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT UNSIGNED NOT NULL,
  `event_type`     VARCHAR(48)     NOT NULL              COMMENT '事件类型（对应 credit_rule.event_type）',
  `delta`          INT             NOT NULL              COMMENT '实际变动值（可能被钳制或日上限截断）',
  `raw_delta`      INT             NOT NULL DEFAULT 0    COMMENT '规则原始分值（便于发现被钳制的情况）',
  `score_before`   INT             NOT NULL,
  `score_after`    INT             NOT NULL,
  `level_before`   VARCHAR(16)     NOT NULL DEFAULT '',
  `level_after`    VARCHAR(16)     NOT NULL DEFAULT '',
  `biz_type`       VARCHAR(32)     NOT NULL DEFAULT ''   COMMENT '关联业务类型 ORDER/DISPUTE/APPEAL/MANUAL',
  `biz_id`         BIGINT UNSIGNED NULL                  COMMENT '关联业务 id（订单/工单）',
  `reason`         VARCHAR(512)    NOT NULL DEFAULT '',
  `operator_type`  VARCHAR(16)     NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM/OPERATOR/ADMIN',
  `operator_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `idempotent_key` VARCHAR(160)    NOT NULL              COMMENT '⭐ 幂等键：eventType + bizId + userId',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),                     -- ⭐ 信誉分幂等，不重复加减分
  KEY `idx_user_time` (`user_id`, `created_at`),                -- 信誉分明细（UC-U-82）
  KEY `idx_event_time` (`event_type`, `created_at`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  CONSTRAINT `ck_credit_after` CHECK (`score_after` BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信誉分流水（只增不改）；记录 level_before/after 便于等级迁移分析（§5.11.3）';

DROP TABLE IF EXISTS `credit_appeal`;
CREATE TABLE `credit_appeal` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT UNSIGNED NOT NULL,
  `credit_log_id`  BIGINT UNSIGNED NOT NULL              COMMENT '被申诉的扣分记录',
  `reason`         VARCHAR(1024)   NOT NULL              COMMENT '申诉理由',
  `evidences`      JSON            NULL                  COMMENT '证据材料 key 数组',
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED/EXPIRED',
  `handler_id`     BIGINT UNSIGNED NULL                  COMMENT '受理的仲裁专员',
  `handle_remark`  VARCHAR(1024)   NOT NULL DEFAULT '',
  `handled_at`     DATETIME        NULL,
  `expire_at`      DATETIME        NOT NULL              COMMENT '申诉截止（扣分后 7 天）',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_log` (`credit_log_id`),                       -- 一条扣分只能申诉一次
  KEY `idx_user` (`user_id`, `status`),
  KEY `idx_status_expire` (`status`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信誉分扣分申诉（UC-U-83 / UC-O-15）；一次性，7 天内有效';
