-- =====================================================================
--  任务撮合平台 · 数据库基线 DDL  V3
--  模块：资金(wallet) + 抽成(commission) + 争端(dispute)
--        + 即时通讯(im) + 通知(notify) + 风控(risk)
--  对应设计：§5.5.4 资金托管 / §5.12 抽成与分成 / §5.6.4 工单状态机
--            §5.8.5 自建风控 / §5.8.8 资金安全专项
--  ⚠️ 本文件所有表 ⛔ 永不物理删除（资金与证据类）
-- =====================================================================

SET NAMES utf8mb4;

-- =====================================================================
--  1. 钱包与托管（wallet）
-- =====================================================================

DROP TABLE IF EXISTS `wallet_account`;
CREATE TABLE `wallet_account` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT UNSIGNED NULL                  COMMENT '用户虚拟账户；平台科目账户为 NULL',
  `account_type`   VARCHAR(24)     NOT NULL DEFAULT 'USER' COMMENT 'USER 用户 / PLATFORM_COMMISSION 平台佣金 / PLATFORM_DEPOSIT 保证金 / ESCROW_HOLD 托管池',
  `account_no`     VARCHAR(32)     NOT NULL              COMMENT '账户号',
  `available`      DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '可用余额',
  `frozen`         DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '冻结余额（托管中/提现审核中）',
  `total_in`       DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '累计收入（对账辅助）',
  `total_out`      DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '累计支出',
  `version`        INT             NOT NULL DEFAULT 0    COMMENT '乐观锁',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_no` (`account_no`),
  UNIQUE KEY `uk_user_type` (`user_id`, `account_type`),
  CONSTRAINT `ck_wallet_non_negative` CHECK (`available` >= 0 AND `frozen` >= 0),
  CONSTRAINT `ck_wallet_type` CHECK (`account_type` IN ('USER','PLATFORM_COMMISSION','PLATFORM_DEPOSIT','ESCROW_HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包账户（双余额：available + frozen）；⭐ available 负数即 bug，CHECK 从库层拦截';

DROP TABLE IF EXISTS `escrow`;
CREATE TABLE `escrow` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `escrow_no`         VARCHAR(32)     NOT NULL,
  `order_id`          BIGINT UNSIGNED NOT NULL,
  `publisher_id`      BIGINT UNSIGNED NOT NULL,
  `amount`            DECIMAL(18,2)   NOT NULL              COMMENT '托管总额',
  `released_amount`   DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '已释放金额',
  `refunded_amount`   DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '已退款金额',
  `status`            VARCHAR(24)     NOT NULL DEFAULT 'CREATED'
                      COMMENT 'CREATED/FROZEN/PARTIAL_RELEASED/RELEASED/FROZEN_DISPUTE/REFUNDED/CANCELLED',
  `channel`           VARCHAR(16)     NOT NULL              COMMENT 'WECHAT/ALIPAY',
  `channel_order_no`  VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '渠道订单号',
  `channel_pay_mode`  VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT 'NATIVE 扫码/JSAPI 小程序/APP SDK（按端不同）',
  `paid_at`           DATETIME        NULL,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_escrow_no` (`escrow_no`),
  UNIQUE KEY `uk_order` (`order_id`),
  UNIQUE KEY `uk_channel_order` (`channel`, `channel_order_no`),
  KEY `idx_status` (`status`),
  CONSTRAINT `ck_escrow_amount` CHECK (`amount` > 0),
  CONSTRAINT `ck_escrow_sum` CHECK (`released_amount` + `refunded_amount` <= `amount`),
  CONSTRAINT `ck_escrow_status` CHECK (`status` IN ('CREATED','FROZEN','PARTIAL_RELEASED','RELEASED','FROZEN_DISPUTE','REFUNDED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='托管单（与主订单 1:1）；⭐ ck_escrow_sum 保证释放+退款不超托管额';

-- ⭐ P0-① 修复：托管份额表
-- 背景：原 escrow 只有总额，无法表达「只冻结 A 的份额、B/C 正常结算」（§5.5.3 / §5.12.4 的核心设计）。
-- 粒度对齐 delivery_unit —— wallet_txn / commission_detail / dispute_ticket 都已带 unit_id，escrow 层此前缺这一环。
DROP TABLE IF EXISTS `escrow_share`;
CREATE TABLE `escrow_share` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `escrow_id`          BIGINT UNSIGNED NOT NULL              COMMENT '所属托管单',
  `order_id`           BIGINT UNSIGNED NOT NULL              COMMENT '冗余，便于按主订单汇总',
  `sub_order_id`       BIGINT UNSIGNED NOT NULL              COMMENT '人维度：子订单',
  `unit_id`            BIGINT UNSIGNED NOT NULL              COMMENT '⭐ 结算单元（delivery_unit）—— 冻结与释放的最小粒度',
  `taker_id`           BIGINT UNSIGNED NOT NULL,
  `share_amount`       DECIMAL(18,2)   NOT NULL              COMMENT '该单元托管份额',
  `released_amount`    DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '已结算',
  `refunded_amount`    DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '已退款',
  `frozen_amount`      DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '⭐ 争议冻结中（只冻这一份，不牵连其他接单方）',
  `status`             VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING/FROZEN/RELEASED/PARTIAL_RELEASED/DISPUTE_FROZEN/REFUNDED/CANCELLED',
  `dispute_ticket_id`  BIGINT UNSIGNED NULL                  COMMENT '冻结来源工单',
  `frozen_at`          DATETIME        NULL,
  `released_at`        DATETIME        NULL,
  `refunded_at`        DATETIME        NULL,
  `version`            INT             NOT NULL DEFAULT 0,
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_unit` (`unit_id`),
  KEY `idx_escrow` (`escrow_id`),
  KEY `idx_sub_order` (`sub_order_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_status` (`status`),
  KEY `idx_dispute` (`dispute_ticket_id`),
  CONSTRAINT `ck_share_amount` CHECK (`share_amount` > 0),
  CONSTRAINT `ck_share_sum` CHECK (`released_amount` + `refunded_amount` + `frozen_amount` <= `share_amount`),
  CONSTRAINT `ck_share_status` CHECK (`status` IN ('PENDING','FROZEN','RELEASED','PARTIAL_RELEASED','DISPUTE_FROZEN','REFUNDED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='⭐ 托管份额（P0-① 修复）；不变量：Σ share_amount == escrow.amount';

DROP TABLE IF EXISTS `wallet_txn`;
CREATE TABLE `wallet_txn` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `txn_no`           VARCHAR(32)     NOT NULL              COMMENT '流水号',
  `biz_type`         VARCHAR(24)     NOT NULL              COMMENT 'ESCROW 托管 / RELEASE 分账 / REFUND 退款 / WITHDRAW 提现 / FEE 佣金 / ADJUST 人工调账 / REVERSE 红字冲销',
  `direction`        VARCHAR(8)      NOT NULL              COMMENT 'DEBIT 借 / CREDIT 贷（复式记账，Σ借=Σ贷）',
  `from_account_id`  BIGINT UNSIGNED NULL                  COMMENT '出账账户（PLATFORM 外部资金流入时为 NULL）',
  `to_account_id`    BIGINT UNSIGNED NULL                  COMMENT '入账账户',
  `amount`           DECIMAL(18,2)   NOT NULL,
  `order_id`         BIGINT UNSIGNED NULL,
  `sub_order_id`     BIGINT UNSIGNED NULL,
  `unit_id`          BIGINT UNSIGNED NULL                  COMMENT '⭐ 结算单元（delivery_unit）',
  `escrow_id`        BIGINT UNSIGNED NULL,
  `balance_after`    DECIMAL(18,2)   NULL                  COMMENT '账户变动后余额（便于对账与排查）',
  `idempotent_key`   VARCHAR(160)    NOT NULL              COMMENT '⭐ 幂等键',
  `channel_txn_no`   VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '渠道流水号（T+1 对账关联键）',
  `status`           VARCHAR(16)     NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/REVERSED',
  `remark`           VARCHAR(512)    NOT NULL DEFAULT '',
  `trace_id`         VARCHAR(64)     NOT NULL DEFAULT '',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_txn_no` (`txn_no`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),                     -- ⭐ 资金幂等（§5.9.1）
  KEY `idx_order` (`order_id`),
  KEY `idx_sub_order` (`sub_order_id`),
  KEY `idx_unit` (`unit_id`),
  KEY `idx_account_time` (`from_account_id`, `created_at`),
  KEY `idx_to_account_time` (`to_account_id`, `created_at`),
  KEY `idx_channel_txn` (`channel_txn_no`),                    -- T+1 对账
  KEY `idx_biz_time` (`biz_type`, `created_at`),
  CONSTRAINT `ck_txn_amount` CHECK (`amount` > 0),
  CONSTRAINT `ck_txn_direction` CHECK (`direction` IN ('DEBIT','CREDIT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='⭐ 资金流水（双分录）；不变量 Σ借方 == Σ贷方，每日校验，不通过即冻结提现（§5.5.4）';


-- =====================================================================
--  2. 抽成与分成（commission）
-- =====================================================================

DROP TABLE IF EXISTS `commission_rule`;
CREATE TABLE `commission_rule` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `rule_type`         VARCHAR(16)     NOT NULL              COMMENT 'BASE 基准 / CATEGORY 分类覆盖 / TIER 金额阶梯 / ACTIVITY 活动',
  `category_id`       BIGINT UNSIGNED NULL                  COMMENT 'CATEGORY 类规则适用分类',
  `amount_min`        DECIMAL(18,2)   NULL                  COMMENT 'TIER 区间下限（含）',
  `amount_max`        DECIMAL(18,2)   NULL                  COMMENT 'TIER 区间上限（不含）',
  `rate`              DECIMAL(6,4)    NOT NULL              COMMENT '费率（0.05 = 5%）',
  `priority`          INT             NOT NULL DEFAULT 100  COMMENT '优先级，越小越优先',
  `stack_credit_delta` TINYINT        NOT NULL DEFAULT 1    COMMENT '是否叠加信誉分等级修正',
  `scope_client`      VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '限定端（活动用，空=不限）',
  `enabled`           TINYINT         NOT NULL DEFAULT 1,
  `effective_from`    DATETIME        NULL,
  `effective_to`      DATETIME        NULL,
  `operator_id`       BIGINT UNSIGNED NULL                  COMMENT '配置人',
  `approval_id`       BIGINT UNSIGNED NULL                  COMMENT '关联审批单（双人审批）',
  `remark`            VARCHAR(512)    NOT NULL DEFAULT '',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_enabled` (`rule_type`, `enabled`, `priority`),
  KEY `idx_category` (`category_id`),
  KEY `idx_effective` (`enabled`, `effective_from`, `effective_to`),
  CONSTRAINT `ck_comm_rate` CHECK (`rate` BETWEEN 0.01 AND 0.20),   -- ⭐ 费率钳制在库层（1%-20%，§4.8.6）
  CONSTRAINT `ck_comm_type` CHECK (`rule_type` IN ('BASE','CATEGORY','TIER','ACTIVITY')),
  CONSTRAINT `ck_comm_tier` CHECK (`rule_type` <> 'TIER' OR (`amount_min` IS NOT NULL AND `amount_max` IS NOT NULL AND `amount_min` < `amount_max`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抽成费率规则（§4.8.2 优先级：活动 > 分类 > 阶梯 > 基准）；⛔ 区间重叠由服务层校验，库层挡不住';

DROP TABLE IF EXISTS `commission_detail`;
CREATE TABLE `commission_detail` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `unit_id`            BIGINT UNSIGNED NOT NULL              COMMENT '⭐ 结算单元（抽成的最小粒度）',
  `sub_order_id`       BIGINT UNSIGNED NOT NULL,
  `order_id`           BIGINT UNSIGNED NOT NULL,
  `taker_id`           BIGINT UNSIGNED NOT NULL,
  `base_amount`        DECIMAL(18,2)   NOT NULL              COMMENT '计费基数',
  `credit_level`       VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT '结算时的信誉等级快照',
  `base_rate`          DECIMAL(6,4)    NOT NULL              COMMENT '基础费率（命中规则的值）',
  `credit_delta`       DECIMAL(6,4)    NOT NULL DEFAULT 0.00 COMMENT '信誉分修正（百分点转小数）',
  `final_rate`         DECIMAL(6,4)    NOT NULL              COMMENT '最终费率（钳制后）',
  `commission_amount`  DECIMAL(18,2)   NOT NULL              COMMENT '佣金',
  `taker_net_amount`   DECIMAL(18,2)   NOT NULL              COMMENT '接单方实得',
  `rule_snapshot`      JSON            NOT NULL              COMMENT '⭐ 命中规则与参数快照 —— 没有它，日后改费率历史订单就说不清',
  `status`             VARCHAR(16)     NOT NULL DEFAULT 'EFFECTIVE' COMMENT 'EFFECTIVE/VOID（重算后原记录置 VOID，⛔ 不删除）',
  `version`            INT             NOT NULL DEFAULT 1    COMMENT '重算版本',
  `idempotent_key`     VARCHAR(160)    NOT NULL,
  `calc_time`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),
  UNIQUE KEY `uk_unit_version` (`unit_id`, `version`),
  KEY `idx_order` (`order_id`),
  KEY `idx_taker_time` (`taker_id`, `calc_time`),
  KEY `idx_status` (`status`),
  CONSTRAINT `ck_comm_balanced` CHECK (`commission_amount` + `taker_net_amount` = `base_amount`),   -- ⭐⭐ 配平不变量（§5.12.3）
  CONSTRAINT `ck_comm_net_positive` CHECK (`taker_net_amount` > 0),
  CONSTRAINT `ck_comm_final_rate` CHECK (`final_rate` BETWEEN 0 AND 0.20)          -- ⭐ P0-② 下限=0：公益类（WELFARE）佣金恒为 0，不能被费率钳制拒绝
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='⭐ 逐结算单元抽成明细；ck_comm_balanced 让"佣金+实得≠基数"的舍入 bug 在写入时就暴露（§5.12.3 红线第 4 条）';


-- =====================================================================
--  3. 充值与提现（wallet）
-- =====================================================================

DROP TABLE IF EXISTS `recharge_order`;
CREATE TABLE `recharge_order` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recharge_no`      VARCHAR(32)     NOT NULL,
  `user_id`          BIGINT UNSIGNED NOT NULL,
  `amount`           DECIMAL(18,2)   NOT NULL,
  `channel`          VARCHAR(16)     NOT NULL              COMMENT 'WECHAT/ALIPAY',
  `pay_mode`         VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT 'NATIVE/JSAPI/APP',
  `channel_order_no` VARCHAR(64)     NOT NULL DEFAULT '',
  `status`           VARCHAR(16)     NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAID/FAILED/CLOSED',
  `paid_at`          DATETIME        NULL,
  `expire_at`        DATETIME        NOT NULL              COMMENT '支付超时（默认 30 分钟）',
  `source_client`    VARCHAR(16)     NOT NULL DEFAULT '',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recharge_no` (`recharge_no`),
  KEY `idx_user_time` (`user_id`, `created_at`),
  KEY `idx_status_expire` (`status`, `expire_at`),
  CONSTRAINT `ck_recharge_amount` CHECK (`amount` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值单（托管资金来源之一）';

DROP TABLE IF EXISTS `withdraw_apply`;
CREATE TABLE `withdraw_apply` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `withdraw_no`     VARCHAR(32)     NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `amount`          DECIMAL(18,2)   NOT NULL,
  `fee`             DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '提现手续费',
  `actual_amount`   DECIMAL(18,2)   NOT NULL              COMMENT '实际到账',
  `bank_name`       VARCHAR(64)     NOT NULL DEFAULT '',
  `bank_account_enc` VARBINARY(512) NULL                  COMMENT '银行卡号密文（L4）',
  `bank_account_masked` VARCHAR(32) NOT NULL DEFAULT ''   COMMENT '脱敏卡号',
  `account_name`    VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '开户名（需与实名一致）',
  `channel`         VARCHAR(16)     NOT NULL DEFAULT ''   COMMENT 'WECHAT/ALIPAY/BANK',
  `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/AUDITING/APPROVING/PROCESSING/SUCCESS/FAILED/REJECTED',
  `audit_by`        BIGINT UNSIGNED NULL                  COMMENT '审核人（财务专员/管理员）',
  `audit_at`        DATETIME        NULL,
  `reject_reason`   VARCHAR(512)    NOT NULL DEFAULT '',
  `channel_txn_no`  VARCHAR(64)     NOT NULL DEFAULT '',
  `idempotent_key`  VARCHAR(160)    NOT NULL              COMMENT '⭐ userId + requestId',
  `risk_score`      INT             NOT NULL DEFAULT 0,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_withdraw_no` (`withdraw_no`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),                     -- ⭐ 提现幂等
  KEY `idx_user_time` (`user_id`, `created_at`),
  KEY `idx_status_time` (`status`, `created_at`),
  CONSTRAINT `ck_withdraw_amount` CHECK (`amount` > 0 AND `actual_amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现申请；超 5000 元转财务专员审核，超 5 万需财务管理员复核（§4.7.5）';

DROP TABLE IF EXISTS `recon_bill`;
CREATE TABLE `recon_bill` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bill_date`      DATE            NOT NULL              COMMENT '账单日期 T-1',
  `channel`        VARCHAR(16)     NOT NULL,
  `bill_type`      VARCHAR(16)     NOT NULL              COMMENT 'PAY 支付/REFUND 退款/PROFIT_SHARE 分账/WITHDRAW 提现',
  `total_count`    INT             NOT NULL DEFAULT 0,
  `total_amount`   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
  `local_count`    INT             NOT NULL DEFAULT 0    COMMENT '本地流水笔数',
  `local_amount`   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
  `diff_count`     INT             NOT NULL DEFAULT 0    COMMENT '差异笔数',
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/MATCHED/DIFF/DONE',
  `file_key`       VARCHAR(512)    NOT NULL DEFAULT ''   COMMENT '账单文件（自动拉取，⛔ 不允许人工上传覆盖）',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_channel_type` (`bill_date`, `channel`, `bill_type`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='T+1 对账账单汇总（§5.5.4）';

DROP TABLE IF EXISTS `recon_diff`;
CREATE TABLE `recon_diff` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bill_id`         BIGINT UNSIGNED NOT NULL,
  `diff_type`       VARCHAR(24)     NOT NULL              COMMENT 'CHANNEL_ONLY 渠道有平台无 / LOCAL_ONLY 平台有渠道无 / AMOUNT_MISMATCH 金额不符',
  `channel_txn_no`  VARCHAR(64)     NOT NULL DEFAULT '',
  `local_txn_id`    BIGINT UNSIGNED NULL,
  `channel_amount`  DECIMAL(18,2)   NULL,
  `local_amount`    DECIMAL(18,2)   NULL,
  `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/HANDLING/RESOLVED/IGNORED',
  `handler_id`      BIGINT UNSIGNED NULL,
  `handle_remark`   VARCHAR(1024)   NOT NULL DEFAULT ''   COMMENT '⭐ 只能"标记+说明"，⛔ 不能直接抹平',
  `handled_at`      DATETIME        NULL,
  `approval_id`     BIGINT UNSIGNED NULL                  COMMENT '大额差异处理需复核',
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_bill` (`bill_id`),
  KEY `idx_status` (`status`, `created_at`),
  KEY `idx_channel_txn` (`channel_txn_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异单（UC-O-14 / UC-A-12）';


-- =====================================================================
--  4. 争端与仲裁（dispute）
-- =====================================================================

DROP TABLE IF EXISTS `dispute_ticket`;
CREATE TABLE `dispute_ticket` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_no`        VARCHAR(32)     NOT NULL,
  `order_id`         BIGINT UNSIGNED NOT NULL,
  `sub_order_id`     BIGINT UNSIGNED NOT NULL              COMMENT '⭐ 关联子订单（MULTI 下必须能只冻结一份份额）',
  `unit_id`          BIGINT UNSIGNED NULL                  COMMENT '关联结算单元（里程碑）',
  `initiator_id`     BIGINT UNSIGNED NOT NULL,
  `respondent_id`    BIGINT UNSIGNED NOT NULL,
  `type`             VARCHAR(32)     NOT NULL              COMMENT 'QUALITY 质量 / OVERDUE 逾期 / REFUSE_ACCEPT 恶意驳回 / NO_DELIVERY 未交付 / OTHER',
  `reason`           VARCHAR(2048)   NOT NULL              COMMENT '诉求描述',
  `demand_amount`    DECIMAL(18,2)   NULL                  COMMENT '期望金额',
  `frozen_amount`    DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '已冻结份额',
  `status`           VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ASSIGNED/EVIDENCE/REVIEWING/PENDING_ADMIN/VERDICT/APPEALED/CLOSED',
  `priority`         VARCHAR(8)      NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/URGENT（金额>5000 自动加急）',
  `handler_id`       BIGINT UNSIGNED NULL                  COMMENT '受理的仲裁专员',
  `assigned_at`      DATETIME        NULL,
  `sla_deadline`     DATETIME        NOT NULL              COMMENT 'SLA 截止（普通24h/加急8h），超时升级',
  `evidence_deadline` DATETIME       NULL                  COMMENT '举证截止',
  `evidence_round`   INT             NOT NULL DEFAULT 0    COMMENT '举证轮次（≤2）',
  `closed_at`        DATETIME        NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_sub_order` (`sub_order_id`),
  KEY `idx_status_sla` (`status`, `sla_deadline`),             -- SLA 升级扫描
  KEY `idx_handler_status` (`handler_id`, `status`),           -- 我的工单队列
  KEY `idx_initiator` (`initiator_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='争端工单（§5.6.4 状态机）；发起时冻结相关份额，⛔ 禁止先分账（§5.12.4）';

DROP TABLE IF EXISTS `dispute_evidence`;
CREATE TABLE `dispute_evidence` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`     BIGINT UNSIGNED NOT NULL,
  `submitter_id`  BIGINT UNSIGNED NOT NULL,
  `side`          VARCHAR(16)     NOT NULL              COMMENT 'INITIATOR 发起方 / RESPONDENT 被诉方',
  `evi_type`      VARCHAR(24)     NOT NULL              COMMENT 'TEXT/IMAGE/FILE/CHAT_LOG/CHECKIN/DELIVERY',
  `content`       TEXT            NULL                  COMMENT '文字说明',
  `file_keys`     JSON            NULL                  COMMENT '附件 key 数组',
  `round`         INT             NOT NULL DEFAULT 1,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ticket` (`ticket_id`, `round`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='争端举证材料；IM 聊天记录可作为证据只读调阅（不可删）';

DROP TABLE IF EXISTS `dispute_verdict`;
CREATE TABLE `dispute_verdict` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`        BIGINT UNSIGNED NOT NULL,
  `verdict_version`  INT             NOT NULL DEFAULT 1    COMMENT '裁决版本（申诉改判则 +1）',
  `handler_id`       BIGINT UNSIGNED NOT NULL              COMMENT '裁决人（仲裁专员）',
  `result`           VARCHAR(24)     NOT NULL              COMMENT 'REFUND_FULL 全额退款 / REFUND_PART 按比例 / RELEASE_FULL 全额结算 / PENALTY 结算+处罚',
  `refund_amount`    DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '退款给发单方金额',
  `release_amount`   DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '结算给接单方金额（税前）',
  `commission_amount` DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '⭐ 按实结金额重算的佣金（不是按原订单金额）',
  `credit_delta_initiator` INT       NOT NULL DEFAULT 0    COMMENT '发起方信誉分变动',
  `credit_delta_respondent` INT      NOT NULL DEFAULT 0    COMMENT '被诉方信誉分变动',
  `reason`           VARCHAR(2048)   NOT NULL              COMMENT '⭐ 裁决理由（必填，用户可见，UC-U-74）',
  `internal_note`    VARCHAR(2048)   NOT NULL DEFAULT ''   COMMENT '内部备注（用户不可见）',
  `approval_id`      BIGINT UNSIGNED NULL                  COMMENT '超 2000 元需管理员复核',
  `idempotent_key`   VARCHAR(160)    NOT NULL              COMMENT '⭐ ticketId + verdictVersion',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),                     -- ⭐ 防重复裁决导致重复退款
  UNIQUE KEY `uk_ticket_version` (`ticket_id`, `verdict_version`),
  KEY `idx_handler` (`handler_id`, `created_at`),
  CONSTRAINT `ck_verdict_result` CHECK (`result` IN ('REFUND_FULL','REFUND_PART','RELEASE_FULL','PENALTY')),
  CONSTRAINT `ck_verdict_amount` CHECK (`refund_amount` >= 0 AND `release_amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裁决书；⭐ 部分退款时 commission_amount 必须按实结金额重算（§4.8.7）';

DROP TABLE IF EXISTS `dispute_timeline`;
CREATE TABLE `dispute_timeline` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`     BIGINT UNSIGNED NOT NULL,
  `action`        VARCHAR(64)     NOT NULL              COMMENT 'CREATE/ASSIGN/EVIDENCE/REVIEW/VERDICT/APPEAL/CLOSE/ESCALATE',
  `from_status`   VARCHAR(16)     NOT NULL DEFAULT '',
  `to_status`     VARCHAR(16)     NOT NULL DEFAULT '',
  `operator_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `operator_type` VARCHAR(16)     NOT NULL DEFAULT 'SYSTEM',
  `content`       VARCHAR(1024)   NOT NULL DEFAULT ''   COMMENT '对外可见的操作说明',
  `internal_note` VARCHAR(1024)   NOT NULL DEFAULT ''   COMMENT '内部备注（用户不可见）',
  `visible_to_user` TINYINT       NOT NULL DEFAULT 1,
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_ticket_time` (`ticket_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单时间轴（UC-U-72 对外展示 / UC-O-09 内部备注）';

DROP TABLE IF EXISTS `dispute_appeal`;
CREATE TABLE `dispute_appeal` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`      BIGINT UNSIGNED NOT NULL,
  `verdict_id`     BIGINT UNSIGNED NOT NULL              COMMENT '被申诉的裁决',
  `appellant_id`   BIGINT UNSIGNED NOT NULL,
  `reason`         VARCHAR(2048)   NOT NULL,
  `evidences`      JSON            NULL,
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REVIEWING/UPHELD 维持/REVERSED 改判/EXPIRED',
  `reviewer_id`    BIGINT UNSIGNED NULL                  COMMENT '⭐ 复核人，⛔ 不得为原裁决人',
  `review_remark`  VARCHAR(2048)   NOT NULL DEFAULT '',
  `reviewed_at`    DATETIME        NULL,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket` (`ticket_id`),                        -- 一次性申诉
  KEY `idx_reviewer` (`reviewer_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裁决申诉（一次性，由另一名仲裁专员复核，§5.13.5 互斥）';


-- =====================================================================
--  5. 即时通讯（im）
-- =====================================================================

DROP TABLE IF EXISTS `im_session`;
CREATE TABLE `im_session` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_key`    VARCHAR(96)     NOT NULL              COMMENT '会话唯一键：小 id + 大 id',
  `user_a`         BIGINT UNSIGNED NOT NULL              COMMENT '较小的 user_id',
  `user_b`         BIGINT UNSIGNED NOT NULL              COMMENT '较大的 user_id',
  `task_id`        BIGINT UNSIGNED NULL                  COMMENT '关联任务（可空=纯私聊）',
  `order_id`       BIGINT UNSIGNED NULL,
  `last_msg_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `last_msg_text`  VARCHAR(256)    NOT NULL DEFAULT ''   COMMENT '冗余最后一条（列表展示，避免 N+1）',
  `last_msg_at`    DATETIME        NULL,
  `unread_a`       INT             NOT NULL DEFAULT 0,
  `unread_b`       INT             NOT NULL DEFAULT 0,
  `pinned_a`       TINYINT         NOT NULL DEFAULT 0    COMMENT 'A 是否置顶',
  `pinned_b`       TINYINT         NOT NULL DEFAULT 0,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_key` (`session_key`),
  KEY `idx_user_a` (`user_a`, `last_msg_at`),
  KEY `idx_user_b` (`user_b`, `last_msg_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IM 会话；⛔ 不做删除（聊天记录是争端证据）';

DROP TABLE IF EXISTS `im_message`;
CREATE TABLE `im_message` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id`    BIGINT UNSIGNED NOT NULL,
  `seq`           BIGINT UNSIGNED NOT NULL              COMMENT '会话内递增序号（⭐ 离线补偿按最大 seq 拉增量）',
  `from_user_id`  BIGINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '0=系统消息',
  `msg_type`      VARCHAR(16)     NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/FILE/ORDER_CARD/SYSTEM',
  `content`       TEXT            NULL,
  `file_key`      VARCHAR(512)    NOT NULL DEFAULT '',
  `extra`         JSON            NULL                  COMMENT '订单卡片等结构化内容',
  `sensitive_hit` TINYINT         NOT NULL DEFAULT 0    COMMENT '是否命中敏感词（用于风控与举证）',
  `risk_tag`      VARCHAR(64)     NOT NULL DEFAULT ''   COMMENT '风控标记：OFF_PLATFORM 脱离平台交易等',
  `recalled`      TINYINT         NOT NULL DEFAULT 0    COMMENT '撤回标记（⛔ 逻辑删除，原文仍保留作证据）',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_seq` (`session_id`, `seq`),
  KEY `idx_from_user` (`from_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='⭐ IM 消息；生产按 session_id hash 分 64 表（§4.5.5 容量测算 17.5 亿行）。6 个月后归档；⛔ 永不物理删除';

DROP TABLE IF EXISTS `im_message_read`;
CREATE TABLE `im_message_read` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id`  BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `max_read_seq` BIGINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '已读到的最大序号（跨端同步）',
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_user` (`session_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已读回执（多端同步）';

DROP TABLE IF EXISTS `im_sensitive_word`;
CREATE TABLE `im_sensitive_word` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `word`       VARCHAR(128)    NOT NULL,
  `category`   VARCHAR(32)     NOT NULL DEFAULT 'OFF_PLATFORM' COMMENT 'OFF_PLATFORM 脱离平台/ABUSE 辱骂/FRAUD 诈骗/OTHER',
  `action`     VARCHAR(16)     NOT NULL DEFAULT 'WARN'  COMMENT 'WARN 提示/BLOCK 拦截/TAG 打标',
  `enabled`    TINYINT         NOT NULL DEFAULT 1,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`),
  KEY `idx_category` (`category`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IM 敏感词库（含变体：威信/vx/V 信，§5.8.5）';


-- =====================================================================
--  6. 通知（notify）
-- =====================================================================

DROP TABLE IF EXISTS `notify_template`;
CREATE TABLE `notify_template` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tpl_code`      VARCHAR(64)     NOT NULL              COMMENT '模板码：TASK_AWARDED/SUB_ORDER_SETTLED/DISPUTE_VERDICT...',
  `channel`       VARCHAR(16)     NOT NULL              COMMENT 'INNER 站内 / SMS / WECHAT_MP 小程序订阅 / PUSH / DESKTOP / EMAIL',
  `title`         VARCHAR(128)    NOT NULL DEFAULT '',
  `content`       VARCHAR(2048)   NOT NULL              COMMENT '模板内容，${var} 占位',
  `ext_config`    JSON            NULL                  COMMENT '渠道参数（如小程序模板 id）',
  `enabled`       TINYINT         NOT NULL DEFAULT 1,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_channel` (`tpl_code`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知模板（UC-A-16）；按端路由不同渠道（§5.15.6）';

DROP TABLE IF EXISTS `notify_record`;
CREATE TABLE `notify_record` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `tpl_code`     VARCHAR(64)     NOT NULL,
  `channel`      VARCHAR(16)     NOT NULL,
  `title`        VARCHAR(128)    NOT NULL DEFAULT '',
  `content`      VARCHAR(2048)   NOT NULL DEFAULT '',
  `biz_type`     VARCHAR(32)     NOT NULL DEFAULT '',
  `biz_id`       BIGINT UNSIGNED NULL,
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED/READ',
  `fail_reason`  VARCHAR(512)    NOT NULL DEFAULT '',
  `retry_count`  INT             NOT NULL DEFAULT 0,
  `idempotent_key` VARCHAR(160)  NOT NULL,
  `sent_at`      DATETIME        NULL,
  `read_at`      DATETIME        NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),                     -- 通知幂等（防 MQ 重试导致重复推送）
  KEY `idx_user_status` (`user_id`, `status`, `created_at`),
  KEY `idx_biz` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知记录（站内信即在此表 channel=INNER）';

DROP TABLE IF EXISTS `notify_preference`;
CREATE TABLE `notify_preference` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `tpl_code`    VARCHAR(64)     NOT NULL              COMMENT '模板码，* 表示默认',
  `channel`     VARCHAR(16)     NOT NULL,
  `enabled`     TINYINT         NOT NULL DEFAULT 1,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tpl_channel` (`user_id`, `tpl_code`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知偏好（UC-U-07）；⛔ 交易类关键通知不可关闭（中标/交付/结算/裁决）';


-- =====================================================================
--  7. 风控（risk，自建全套）
-- =====================================================================

DROP TABLE IF EXISTS `risk_rule`;
CREATE TABLE `risk_rule` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `rule_code`      VARCHAR(64)     NOT NULL,
  `rule_name`      VARCHAR(128)    NOT NULL,
  `event_type`     VARCHAR(48)     NOT NULL              COMMENT 'LOGIN/REGISTER/PUBLISH/GRAB/BID/PAY/WITHDRAW/IM',
  `condition_expr` VARCHAR(2048)   NOT NULL              COMMENT '条件表达式（DSL/SpEL），如 count(login_fail,userId,10m)>=5',
  `action`         VARCHAR(16)     NOT NULL              COMMENT 'PASS/TAG/CHALLENGE/REJECT',
  `score`          INT             NOT NULL DEFAULT 0    COMMENT '风险分累加值',
  `mode`           VARCHAR(8)      NOT NULL DEFAULT 'TAG' COMMENT '⭐ 运行模式：TAG 观察（不拦截，用于回测误杀）/ LIVE 生效',
  `priority`       INT             NOT NULL DEFAULT 100,
  `enabled`        TINYINT         NOT NULL DEFAULT 1,
  `effective_from` DATETIME        NULL,
  `effective_to`   DATETIME        NULL,
  `operator_id`    BIGINT UNSIGNED NULL,
  `remark`         VARCHAR(512)    NOT NULL DEFAULT '',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_code` (`rule_code`),
  KEY `idx_event_enabled` (`event_type`, `enabled`, `priority`),
  CONSTRAINT `ck_risk_action` CHECK (`action` IN ('PASS','TAG','CHALLENGE','REJECT')),
  CONSTRAINT `ck_risk_mode` CHECK (`mode` IN ('TAG','LIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控规则（§5.8.5）；⭐ 新规则必须先以 TAG 模式跑 3 天看误杀率（R22 对策）';

DROP TABLE IF EXISTS `risk_hit_log`;
CREATE TABLE `risk_hit_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_type`    VARCHAR(48)     NOT NULL,
  `user_id`       BIGINT UNSIGNED NULL,
  `device_id`     VARCHAR(128)    NOT NULL DEFAULT '',
  `ip`            VARCHAR(64)     NOT NULL DEFAULT '',
  `hit_rules`     JSON            NOT NULL              COMMENT '命中的规则数组（含各自 score）',
  `total_score`   INT             NOT NULL DEFAULT 0,
  `final_action`  VARCHAR(16)     NOT NULL              COMMENT 'PASS/TAG/CHALLENGE/REJECT',
  `biz_type`      VARCHAR(32)     NOT NULL DEFAULT '',
  `biz_id`        BIGINT UNSIGNED NULL,
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `created_at`),
  KEY `idx_action_time` (`final_action`, `created_at`),
  KEY `idx_event_time` (`event_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='⭐ 风控决策日志；可解释性是硬要求 —— 用户申诉时必须能说出"哪条规则拦了你"（§5.8.5）';

DROP TABLE IF EXISTS `device_fingerprint`;
CREATE TABLE `device_fingerprint` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `device_id`     VARCHAR(128)    NOT NULL              COMMENT '设备指纹哈希',
  `client`        VARCHAR(16)     NOT NULL DEFAULT '',
  `first_user_id` BIGINT UNSIGNED NULL                  COMMENT '首个关联账号',
  `user_count`    INT             NOT NULL DEFAULT 0    COMMENT '关联账号数（>3 预警）',
  `risk_level`    VARCHAR(16)     NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/SUSPECT/BLACKLIST',
  `attrs`         JSON            NULL                  COMMENT '设备属性（机型/系统/分辨率等，⛔ 不含可识别个人信息）',
  `first_seen_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_seen_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device` (`device_id`),
  KEY `idx_risk` (`risk_level`),
  KEY `idx_user_count` (`user_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备指纹（§5.8.5）；⛔ 不得用于跨站追踪（合规红线）';

DROP TABLE IF EXISTS `blacklist`;
CREATE TABLE `blacklist` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `list_type`    VARCHAR(16)     NOT NULL              COMMENT 'USER/DEVICE/IP/PHONE/BANK_CARD',
  `list_value`   VARCHAR(256)    NOT NULL              COMMENT '值（手机号/卡号存哈希）',
  `reason`       VARCHAR(512)    NOT NULL,
  `source`       VARCHAR(32)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 人工/RISK 风控/APPEAL 申诉',
  `expire_at`    DATETIME        NULL                  COMMENT 'NULL=永久',
  `operator_id`  BIGINT UNSIGNED NULL,
  `enabled`      TINYINT         NOT NULL DEFAULT 1,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_value` (`list_type`, `list_value`),
  KEY `idx_enabled_expire` (`enabled`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='黑名单（UC-A-11）；解封需超管审批 + 缴保证金（§4.9.5）';
