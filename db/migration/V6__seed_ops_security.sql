-- =====================================================================
--  任务撮合平台 · 种子数据 V6：运维与安全补充表的种子
--  内容：平台科目账户初始化 · IP 白名单 · 限流规则 · 特性开关（含应急）
--        · MQ 事件注册表 · 归档任务注册
--  对应修复：P1-⑨ 归档 · P1-⑫ IP 白名单 · P2-⑭ 限流/开关 · P2-⑯ 事件口径
--            P2-⑰ 平台科目账户
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. ⭐ P2-⑰ 平台科目账户初始化
--    背景：wallet_account.account_type 支持 PLATFORM_COMMISSION / PLATFORM_DEPOSIT /
--          ESCROW_HOLD，但此前没有任何 INSERT —— 佣金与托管资金没有落账主体。
--    ⚠️ 这三类账户 user_id 为 NULL，与用户账户通过 uk_user_type 区分。
-- ---------------------------------------------------------------------
INSERT INTO `wallet_account`
(`user_id`,`account_type`,`account_no`,`available`,`frozen`,`total_in`,`total_out`) VALUES
(NULL,'PLATFORM_COMMISSION','PLAT-COMM-0001',   0.00, 0.00, 0.00, 0.00),
(NULL,'PLATFORM_DEPOSIT',   'PLAT-DEP-0001',    0.00, 0.00, 0.00, 0.00),
(NULL,'ESCROW_HOLD',        'PLAT-ESCROW-0001', 0.00, 0.00, 0.00, 0.00);

-- ---------------------------------------------------------------------
-- 2. P1-⑫ 访问白名单（示例值；生产按实际网段替换）
-- ---------------------------------------------------------------------
INSERT INTO `sys_access_whitelist` (`scope`,`list_type`,`list_value`,`remark`) VALUES
('ADMIN_CONSOLE','VPN_SEGMENT','10.8.0.0/16',   '办公 VPN 网段（管理后台仅此可达）'),
('ADMIN_CONSOLE','IP',         '127.0.0.1',     '本机调试'),
('PAY_CALLBACK', 'IP',         '0.0.0.0/0',     '⚠️ 占位：上线前必须替换为微信/支付宝官方回调 IP 段，否则回调不设来源限制'),
('OPEN_API',     'VPN_SEGMENT','10.8.0.0/16',   '开放接口预留');

-- ---------------------------------------------------------------------
-- 3. P2-⑭-a 限流规则（原只写在设计文档 §5.9.5，现落表可热更新 + 可审计）
-- ---------------------------------------------------------------------
INSERT INTO `sys_rate_limit_rule`
(`rule_code`,`resource`,`dimension`,`threshold`,`window_seconds`,`action`,`remark`) VALUES
('RL_GRAB_USER',      '/api/v1/tasks/*/grab',            'USER',     10,   60, 'REJECT',  '抢单：用户级（防脚本刷抢）'),
('RL_GRAB_GLOBAL',    '/api/v1/tasks/*/grab',            'GLOBAL', 3000,    1, 'REJECT',  '抢单：接口级兜底（核心链路，⛔ 不降级）'),
('RL_LOGIN_ACCOUNT',  '/api/v1/auth/login',              'USER',      5,  900, 'CAPTCHA', '登录失败锁定：账号级（5 次/15 分钟）'),
('RL_LOGIN_IP',       '/api/v1/auth/login',              'IP',       50,   60, 'CAPTCHA', '登录：IP 级（撞库特征）'),
('RL_SMS_PHONE',      '/api/v1/auth/sms-code',           'USER',      5, 3600, 'REJECT',  '短信：同号小时上限（防验证码轰炸）'),
('RL_SMS_IP',         '/api/v1/auth/sms-code',           'IP',       20, 3600, 'REJECT',  '短信：IP 级'),
('RL_TASK_LIST',      '/api/v1/tasks',                   'USER',     60,   60, 'DEGRADE', '任务大厅：超限走缓存兜底'),
('RL_HALL_GLOBAL',    '/api/v1/tasks',                   'GLOBAL', 8000,    1, 'DEGRADE', '大厅：接口级'),
('RL_IM_SEND',        '/api/v1/im/sessions/*/messages',  'USER',     30,   60, 'REJECT',  'IM 发消息：用户级'),
('RL_EXPORT',         '/api/v1/*/export',                'USER',      2,   60, 'REJECT',  '导出：用户级（并写访问审计）'),
('RL_CALLBACK',       '/api/v1/pay/callback/*',          'CHANNEL',5000,    1, 'REJECT',  '支付回调：按渠道（另配 IP 白名单，⛔ 不降级）'),
('RL_DISPUTE_CREATE', '/api/v1/disputes',                'USER',      5, 3600, 'REJECT',  '发起争端：用户级');

-- ---------------------------------------------------------------------
-- 4. P2-⑭-b 特性开关（⭐ emergency=1 的是应急开关，必须能一键切换）
-- ---------------------------------------------------------------------
INSERT INTO `sys_feature_flag` (`flag_key`,`flag_value`,`value_type`,`description`,`emergency`) VALUES
('emergency.freeze_withdraw',      '0','BOOL','⭐ 应急：冻结全部提现（资金不变量校验失败时自动置 1）',1),
('emergency.stop_settlement',      '0','BOOL','⭐ 应急：停止全部分账结算',1),
('emergency.stop_payout',          '0','BOOL','⭐ 应急：停止代付/提现打款',1),
('emergency.site_readonly',        '0','BOOL','⭐ 应急：全站只读（保留查询，禁止写操作）',1),
('emergency.disable_new_task',     '0','BOOL','⭐ 应急：禁止发布新任务',1),
('feature.partial_settlement',     '0','BOOL','分批结算开关（Q26：Phase 1 关闭，Phase 2 开放）',0),
('feature.milestone_multi',        '0','BOOL','里程碑 × 多人拆单组合（Q21：Phase 1 关闭）',0),
('feature.anti_snipe',             '1','BOOL','竞拍防狙击（最后 5 分钟有新报价则延长）',0),
('feature.auto_accept',            '1','BOOL','⭐ 超时自动验收（偏向接单方的核心条款，⛔ 不建议关闭）',0),
('feature.credit_decay',           '1','BOOL','信誉分时间衰减',0),
('feature.ai_audit',               '0','STRING','机审模型版本；0=仅规则引擎，Phase 2 接模型',0),
('feature.newbie_boost',           '1','BOOL','新手单固定曝光位（冷启动保护）',0),
('feature.risk_rule_live',         '0','BOOL','风控规则是否生效（0=全部 TAG 观察模式，用于回测误杀率）',0);

-- ---------------------------------------------------------------------
-- 5. ⭐ P2-⑯ MQ 事件注册表（缓存刷新 + 领域事件 + 延时消息的口径来源）
-- ---------------------------------------------------------------------
INSERT INTO `sys_event_registry`
(`topic`,`event_code`,`event_name`,`producer`,`consumers`,`ordered`,`msg_type`,`idempotent_hint`) VALUES
-- 缓存刷新广播（多实例本地缓存失效）
('cache-refresh','CACHE_INVALIDATE',      '缓存失效广播',   'admin',   JSON_ARRAY('identity','task','credit','wallet','admin'), 0,'NORMAL','cacheKey'),
-- 任务与接单
('domain-event', 'TASK_PUBLISHED',        '任务已发布',     'task',    JSON_ARRAY('notify','risk'),                              0,'NORMAL','taskId'),
('domain-event', 'TASK_AWARDED',          '任务已中标',     'award',   JSON_ARRAY('order','wallet','notify','credit'),           1,'TRANSACTION','taskId+awardMode+bidIds'),
('domain-event', 'TASK_EXPIRED',          '任务已流标',     'task',    JSON_ARRAY('wallet','notify'),                            0,'NORMAL','taskId'),
-- 履约
('domain-event', 'SUB_ORDER_DELIVERED',   '已提交交付',     'order',   JSON_ARRAY('notify','task'),                              0,'NORMAL','subOrderId+round'),
('domain-event', 'SUB_ORDER_ACCEPTED',    '验收通过',       'order',   JSON_ARRAY('wallet','notify','credit'),                   1,'TRANSACTION','subOrderId+targetStatus'),
('domain-event', 'SUB_ORDER_REJECTED',    '交付被驳回',     'order',   JSON_ARRAY('notify'),                                     0,'NORMAL','subOrderId+round'),
('domain-event', 'SUB_ORDER_SETTLED',     '子订单已结算',   'wallet',  JSON_ARRAY('notify','credit','admin'),                    1,'TRANSACTION','unitId+version'),
-- 资金
('pay-callback', 'ESCROW_PAID',           '托管支付成功',   'wallet',  JSON_ARRAY('order','notify','task'),                      1,'TRANSACTION','channel+channelOrderNo+eventType'),
('pay-callback', 'REFUND_DONE',           '退款完成',       'wallet',  JSON_ARRAY('order','notify'),                             1,'TRANSACTION','escrowId+subOrderId+REFUND'),
('pay-callback', 'PROFIT_SHARE_DONE',     '分账完成',       'wallet',  JSON_ARRAY('notify','admin'),                             1,'TRANSACTION','escrowId+unitId+RELEASE'),
-- 信誉分
('domain-event', 'CREDIT_LEVEL_CHANGED',  '信誉等级变化',   'credit',  JSON_ARRAY('notify','identity','task'),                   0,'NORMAL','userId+levelAfter'),
('domain-event', 'CREDIT_FRAUD_DETECTED', '刷分行为核实',   'risk',    JSON_ARRAY('credit','admin','notify'),                    0,'NORMAL','userId+eventType'),
-- 争端
('domain-event', 'DISPUTE_OPENED',        '争端已发起',     'dispute', JSON_ARRAY('wallet','notify'),                            1,'TRANSACTION','ticketId'),
('domain-event', 'DISPUTE_VERDICT_ISSUED','裁决已下达',     'dispute', JSON_ARRAY('wallet','credit','notify'),                   1,'TRANSACTION','ticketId+verdictVersion'),
-- 延时消息
('domain-event', 'AUTO_ACCEPT_DUE',       '验收到期（延时）','order',  JSON_ARRAY('wallet','notify'),                            1,'DELAY','subOrderId+unitId'),
('domain-event', 'TASK_BID_DEADLINE_DUE', '报价截止（延时）','task',   JSON_ARRAY('award','notify'),                             0,'DELAY','taskId'),
('domain-event', 'DISPUTE_SLA_DUE',       '工单 SLA 到期',  'dispute', JSON_ARRAY('notify','admin'),                             0,'DELAY','ticketId'),
-- 归档
('domain-event', 'ARCHIVE_BATCH_DONE',    '归档批次完成',   'admin',   JSON_ARRAY('admin'),                                      0,'NORMAL','table+batchNo');

-- ---------------------------------------------------------------------
-- 6. P1-⑨ 归档任务注册（原 XXL-Job 清单里只有「消息归档」一条，无表结构、无路由规则）
--    ⚠️ 本表只登记归档口径；归档库（*_archive）与查询路由规则见设计 §5.9.4
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_archive_config`;
CREATE TABLE `sys_archive_config` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `table_name`     VARCHAR(64)     NOT NULL              COMMENT '热表名',
  `archive_table`  VARCHAR(64)     NOT NULL              COMMENT '归档表名（同结构，独立库）',
  `keep_days`      INT             NOT NULL              COMMENT '热表保留天数',
  `batch_size`     INT             NOT NULL DEFAULT 5000 COMMENT '单批归档条数（防长事务）',
  `time_column`    VARCHAR(32)     NOT NULL DEFAULT 'created_at' COMMENT '归档依据列',
  `job_cron`       VARCHAR(64)     NOT NULL DEFAULT '0 0 4 * * ?' COMMENT 'XXL-Job 调度表达式',
  `enabled`        TINYINT         NOT NULL DEFAULT 1,
  `last_run_at`    DATETIME        NULL,
  `last_batch_no`  BIGINT UNSIGNED NULL,
  `remark`         VARCHAR(256)    NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_table` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归档任务注册（P1-⑨）；查询路由：热表命中不到时按需回查归档库';

INSERT INTO `sys_archive_config`
(`table_name`,`archive_table`,`keep_days`,`batch_size`,`job_cron`,`remark`) VALUES
('im_message',            'im_message_archive',            180,  5000,'0 30 4 * * ?','IM 消息（3 年 17.5 亿行，最先膨胀；生产按 session_id hash 分 64 表）'),
('notify_record',         'notify_record_archive',          90,  5000,'0 0 4 * * ?', '通知记录（DAU 8万×人均5条 = 40万/日，3 年 4.4 亿行）'),
('sys_login_log',         'sys_login_log_archive',         180,  5000,'0 10 4 * * ?','登录日志（等保要求留存 ≥6 个月，故 180 天）'),
('sys_data_access_log',   'sys_data_access_log_archive',   365,  5000,'0 20 4 * * ?','访问审计（L3/L4 访问留痕，保留更久）'),
('risk_hit_log',          'risk_hit_log_archive',          180,  5000,'0 40 4 * * ?','风控决策日志'),
('task_status_log',       'task_status_log_archive',       365,  5000,'0 50 4 * * ?','任务状态迁移留痕（审计需要，保留 1 年）'),
('wallet_txn',            'wallet_txn_2027',               730,  5000,'0 0 5 1 1 ?', '⚠️ 资金流水按年分表（§4.5.5），此条只做冷表切换登记'),
('sub_order',             'sub_order_archive',             730,  5000,'0 30 5 1 1 ?', '已结算子订单（归档后仍可查，但不再参与热路径统计）');

-- ---------------------------------------------------------------------
-- 7. 校验
-- ---------------------------------------------------------------------
-- 平台科目账户必须齐三类
SELECT 'CHECK_FAIL: 平台科目账户缺失' AS msg, t.at
  FROM (SELECT 'PLATFORM_COMMISSION' AS at UNION ALL SELECT 'PLATFORM_DEPOSIT' UNION ALL SELECT 'ESCROW_HOLD') t
 WHERE NOT EXISTS (SELECT 1 FROM `wallet_account` w WHERE w.account_type = t.at);

-- 应急开关必须存在（缺了就没有一键止血能力）
SELECT 'CHECK_FAIL: 应急开关缺失' AS msg
 WHERE (SELECT COUNT(*) FROM `sys_feature_flag` WHERE `emergency` = 1) < 5;

-- 限流规则必须覆盖抢单与支付回调（核心链路）
SELECT 'CHECK_FAIL: 核心链路缺限流规则' AS msg
 WHERE NOT EXISTS (SELECT 1 FROM `sys_rate_limit_rule` WHERE `resource` LIKE '%grab%')
    OR NOT EXISTS (SELECT 1 FROM `sys_rate_limit_rule` WHERE `resource` LIKE '%callback%');

-- 归档登记必须覆盖 im_message（最先膨胀的表）
SELECT 'CHECK_FAIL: im_message 未登记归档' AS msg
 WHERE NOT EXISTS (SELECT 1 FROM `sys_archive_config` WHERE `table_name` = 'im_message');
