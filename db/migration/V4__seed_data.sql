-- =====================================================================
--  任务撮合平台 · 种子数据  V4
--  内容：角色岗位 / 权限点 / 互斥岗位 / 信誉等级与计分规则 / 抽成费率
--        / 任务分类 / 系统参数 / 端能力矩阵 / 敏感词 / 通知模板
--  对应设计：§4.7 角色 §4.8 抽成 §4.9 信誉分 §4.10 任务类型
--            §5.13 RBAC §5.15 多端 §5.8.5 风控
--  ⚠️ 本文件是**幂等基线**：仅在初始化时执行一次（Flyway V4）
-- =====================================================================

SET NAMES utf8mb4;

-- =====================================================================
--  1. 角色（=岗位）；平台身份 3 个不变，岗位靠这张表细分
-- =====================================================================
INSERT INTO `sys_role` (`role_code`,`role_name`,`identity`,`data_scope`,`builtin`,`description`) VALUES
('ROLE_USER',          '普通用户',     'USER',     'SELF', 1, '平台外部用户：既能发任务也能接任务，同一账号双向'),
('ROLE_CERTIFIED',     '平台认证用户', 'USER',     'SELF', 1, '人工授予；可接定向邀请单，费率可议（§4.9.2）'),
('ROLE_AUDITOR',       '内容审核员',   'OPERATOR', 'ALL',  1, '机审命中任务的复核、违规下架、举报核实'),
('ROLE_RN_AUDITOR',    '实名审核员',   'OPERATOR', 'ALL',  1, '实名材料审核；唯一可查看 L4 身份证原件的岗位'),
('ROLE_ARBITER',       '仲裁专员',     'OPERATOR', 'ALL',  1, '争端工单受理、组织举证、下达裁决（≤2000 元）、受理扣分申诉'),
('ROLE_SUPPORT',       '客服专员',     'OPERATOR', 'ALL',  1, '咨询答复、工单受理与分派、催办、申诉初审'),
('ROLE_FINANCE',       '财务专员',     'OPERATOR', 'ALL',  1, '提现审核（5000-50000）、对账差异处理、退款复核'),
('ROLE_SUPER_ADMIN',   '超级管理员',   'ADMIN',    'ALL',  1, '内部账号管理、角色权限配置、互斥岗位配置'),
('ROLE_OPS_ADMIN',     '运营管理员',   'ADMIN',    'ALL',  1, '分类管理、抽成费率与活动配置、公告、通知模板'),
('ROLE_RISK_ADMIN',    '风控管理员',   'ADMIN',    'ALL',  1, '风控规则、黑名单、信誉分规则与人工调整'),
('ROLE_FINANCE_ADMIN', '财务管理员',   'ADMIN',    'ALL',  1, '对账终审、大额提现终审、大额资金动作双人复核');

-- =====================================================================
--  2. 权限点（命名：domain:resource:action）
--     ⛔ 必须与代码里的 @PreAuthorize 一一对应，由 CI 校验（§5.13.2）
-- =====================================================================
INSERT INTO `sys_permission` (`perm_code`,`perm_name`,`module`,`perm_type`,`risk_level`) VALUES
-- 账号与权限（仅 ADMIN）
('account:user:view',        '查看用户详情',       'admin','API','MEDIUM'),
('account:user:freeze',      '冻结账号',           'admin','API','HIGH'),
('account:user:unfreeze',    '解冻账号',           'admin','API','HIGH'),
('account:user:reset-pwd',   '重置用户密码',       'admin','API','HIGH'),
('account:user:kick',        '强制下线',           'admin','API','MEDIUM'),
('account:internal:manage',  '内部账号管理',       'admin','API','HIGH'),
('rbac:role:manage',         '角色管理',           'admin','API','HIGH'),
('rbac:permission:manage',   '权限点配置',         'admin','API','HIGH'),
('rbac:conflict:manage',     '互斥岗位配置',       'admin','API','HIGH'),
('approval:vote',            '复核投票',           'admin','API','HIGH'),
-- 审核
('realname:audit',           '实名认证审核',       'admin','API','HIGH'),
('task:audit',               '任务内容审核',       'task','API','MEDIUM'),
('task:takedown',            '任务下架',           'task','API','HIGH'),
-- 争端
('dispute:handle',           '工单受理与举证组织', 'dispute','API','MEDIUM'),
('dispute:verdict',          '下达裁决',           'dispute','API','HIGH'),
('dispute:appeal:review',    '申诉复核改判',       'dispute','API','HIGH'),
('ticket:intake',            '工单受理与分派',     'dispute','API','LOW'),
-- 信誉分
('credit:rule:manage',       '信誉分规则配置',     'credit','API','HIGH'),
('credit:level:manage',      '信誉等级配置',       'credit','API','HIGH'),
('credit:adjust',            '信誉分人工调整',     'credit','API','HIGH'),
('credit:appeal:handle',     '扣分申诉处理',       'credit','API','MEDIUM'),
-- 抽成与分类
('commission:rule:manage',   '抽成费率配置',       'admin','API','HIGH'),
('commission:activity:manage','活动费率配置',      'admin','API','HIGH'),
('category:manage',          '任务分类管理',       'task','API','MEDIUM'),
-- 资金
('withdraw:audit',           '提现审核',           'wallet','API','HIGH'),
('recon:view',               '对账查看',           'wallet','API','MEDIUM'),
('recon:handle',             '对账差异处理',       'wallet','API','HIGH'),
('fund:adjust',              '人工调账/补单',      'wallet','API','HIGH'),
('fund:dual-approve',        '资金动作复核',       'wallet','API','HIGH'),
-- 风控
('risk:rule:manage',         '风控规则配置',       'admin','API','HIGH'),
('risk:blacklist:manage',    '黑名单管理',         'admin','API','HIGH'),
-- 平台
('config:manage',            '系统参数配置',       'admin','API','HIGH'),
('notice:manage',            '公告管理',           'admin','API','MEDIUM'),
('tpl:manage',               '通知模板管理',       'admin','API','LOW'),
('report:view',              '报表看板查看',       'admin','API','LOW'),
('report:export',            '数据导出',           'admin','API','HIGH'),
-- 审计
('audit:log:view',           '审计日志查看',       'admin','API','HIGH'),
('audit:log:export',         '审计日志导出',       'admin','API','HIGH');

-- =====================================================================
--  3. 角色-权限绑定
-- =====================================================================
-- 内容审核员
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
 WHERE r.role_code='ROLE_AUDITOR' AND p.perm_code IN ('task:audit','task:takedown','account:user:view','report:view');

-- 实名审核员（唯一可看 L4 身份证原件的岗位）
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
 WHERE r.role_code='ROLE_RN_AUDITOR' AND p.perm_code IN ('realname:audit','account:user:view','report:view');

-- 仲裁专员（⛔ 无任何资金执行权限）
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
 WHERE r.role_code='ROLE_ARBITER' AND p.perm_code IN
       ('dispute:handle','dispute:verdict','dispute:appeal:review','credit:appeal:handle','account:user:view','report:view');

-- 客服专员
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
 WHERE r.role_code='ROLE_SUPPORT' AND p.perm_code IN ('ticket:intake','account:user:view','report:view');

-- 财务专员（⛔ 无裁决权限）
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
 WHERE r.role_code='ROLE_FINANCE' AND p.perm_code IN ('withdraw:audit','recon:view','recon:handle','report:view');

-- 超级管理员：全部权限
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r CROSS JOIN `sys_permission` p WHERE r.role_code='ROLE_SUPER_ADMIN';

-- 运营管理员（⛔ 无资金与权限配置）
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
 WHERE r.role_code='ROLE_OPS_ADMIN' AND p.perm_code IN
       ('commission:rule:manage','commission:activity:manage','category:manage','notice:manage','tpl:manage','report:view');

-- 风控管理员
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
 WHERE r.role_code='ROLE_RISK_ADMIN' AND p.perm_code IN
       ('credit:rule:manage','credit:level:manage','credit:adjust','risk:rule:manage','risk:blacklist:manage',
        'account:user:freeze','account:user:view','report:view');

-- 财务管理员
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
 WHERE r.role_code='ROLE_FINANCE_ADMIN' AND p.perm_code IN
       ('withdraw:audit','recon:view','recon:handle','fund:adjust','fund:dual-approve','report:view','report:export');

-- =====================================================================
--  4. 互斥岗位对（§4.7.5）—— 分配角色时强制校验
-- =====================================================================
INSERT INTO `sys_role_conflict` (`role_code_a`,`role_code_b`,`reason`) VALUES
('ROLE_AUDITOR',      'ROLE_ARBITER',       '防止"自己审过的任务自己裁"'),
('ROLE_ARBITER',      'ROLE_FINANCE',       '裁决权与资金执行权分离'),
('ROLE_ARBITER',      'ROLE_FINANCE_ADMIN', '裁决权与资金执行权分离'),
('ROLE_OPS_ADMIN',    'ROLE_FINANCE_ADMIN', '定价权（费率）与资金核对权分离'),
-- Q15（2026-10-15 拍板）：再加 2 对
('ROLE_RISK_ADMIN',   'ROLE_ARBITER',       '防止「我定的风控规则我来裁」'),
('ROLE_AUDITOR',      'ROLE_RN_AUDITOR',    '防止一人同时看全用户内容与 L4 实名证件');

-- =====================================================================
--  5. 信誉等级与权益矩阵（§4.9.2）
--     ⭐ 区间 0-100，初始 60；「新用户不是满分」是等级机制能成立的前提
-- =====================================================================
INSERT INTO `credit_level`
(`level_code`,`level_name`,`min_score`,`max_score`,`badge`,`award_mode`,`max_ongoing_tasks`,`grab_threshold`,
 `min_task_amount`,`max_task_amount`,`sort_weight`,`commission_delta`,`withdraw_days`,
 `require_deposit`,`support_priority`,`publish_weight`,`force_escrow`,`max_publish_amount`) VALUES
('BLACKLIST','黑名单',0,39,'⛔','AUTO', 0, 999, NULL,        NULL,     0.00, 0.0000, 30, 0,  10, 0.00, 1, 0.00),
('POOR','较差',40,59,'🟠','AUTO', 1,  60, 0.00,      500.00,   0.50, 0.0200,  7, 1,  20, 0.50, 1, 2000.00),
('NORMAL','一般',60,69,'🟡','AUTO', 3,  60, 0.00,      50000.00, 1.00, 0.0000,  3, 0, 100, 1.00, 0, 50000.00),
('GOOD','良好',70,79,'🔵','AUTO', 5,  60, 0.00,      50000.00, 1.30,-0.0050,  1, 0, 100, 1.20, 0, 50000.00),
('EXCELLENT','优秀',80,100,'🟢','AUTO', 8,  60, 0.00,  99999999.00, 1.60,-0.0100,  0, 0,  50, 1.50, 0,200000.00),
('CERTIFIED','平台认证',  80,100,'⭐','MANUAL',10,  60, 0.00,  99999999.00, 1.80,-0.0150,  0, 0,  30, 1.60, 0,200000.00);

-- =====================================================================
--  6. 信誉分计分规则（§4.9.3 接单方 / §4.9.4 发单方）
--     ⭐ 惩罚性扣分 decayable=0（永不随时间衰减）
-- =====================================================================
INSERT INTO `credit_rule` (`event_type`,`event_name`,`role_side`,`delta`,`daily_cap`,`decayable`,`description`) VALUES
-- 接单方 · 加分
('FINISH_5STAR',        '完成订单并获5星好评',   'TAKER',     3, 10, 1, '单日加分合计 ≤10'),
('FINISH_4STAR',        '完成订单并获4星',       'TAKER',     2, 10, 1, ''),
('FINISH_3STAR',        '完成订单并获3星',       'TAKER',     1, 10, 1, ''),
('FINISH_DEFAULT',      '完成订单（超时默认好评）','TAKER',    2, 10, 1, '视同4星'),
('HIGH_VALUE_BONUS',    '高价值订单完成奖励',    'TAKER',     2, 10, 1, '≥5000元，需人工抽查10%'),
('STREAK_5',            '连续5单零异常',         'TAKER',     5, 10, 1, '每满5单触发'),
-- 接单方 · 扣分
('GRAB_ABANDON',        '中标后主动放弃',        'TAKER',   -10,  0, 0, '未开始履约'),
('OVERDUE',             '逾期未交付',            'TAKER',   -15,  0, 1, '可申诉；时间衰减允许'),
('DELIVERY_REJECTED',   '交付被驳回（责任在己）', 'TAKER',    -3,  0, 1, '单订单最多 -9'),
('VERDICT_LOSE',        '裁决败诉',              'TAKER',   -20,  0, 0, '⛔ 永久不衰减'),
('OFF_PLATFORM',        '被举报核实（脱离平台/加价）','TAKER',-10, 0, 0, '按情节 -10~-30'),
('CREDIT_FRAUD',        '刷分行为核实',          'TAKER',  -100,  0, 0, '清零+封号，无申诉'),
('APPEAL_OK_TAKER',     '申诉成功（原判有误）',   'TAKER',     2,  0, 0, '扣分全额回滚并补偿'),
-- 发单方 · 加分
('PUBLISH_FINISH',      '正常验收并评价',        'PUBLISHER', 3, 10, 1, ''),
-- 发单方 · 扣分
('ACCEPT_TIMEOUT',      '验收超时（触发自动验收）','PUBLISHER',-5, 0, 1, '单月最多 -15'),
('MALICIOUS_REJECT',    '无正当理由驳回，被裁决支持接单方','PUBLISHER',-15, 0, 0, '约束"恶意驳回"的核心条款'),
('TASK_TAKEDOWN',       '发布违规任务被下架',    'PUBLISHER',-10, 0, 0, ''),
('CANCEL_AFTER_AWARD',  '中标后取消订单',        'PUBLISHER',-10, 0, 0, ''),
('TASK_MISMATCH',       '任务描述与实际严重不符','PUBLISHER',-10, 0, 0, '多人投诉核实'),
('REFUSE_FULFILL',      '裁决后仍拒不履行',      'PUBLISHER',-25, 0, 0, '并冻结发单权限'),
('FAKE_TASK',           '虚假任务/骗方案',       'PUBLISHER',-100,0, 0, '清零+封号'),
('APPEAL_OK_PUBLISHER', '申诉成功（原判有误）',  'PUBLISHER', 2, 0, 0, '扣分全额回滚并补偿');

-- =====================================================================
--  7. 抽成费率规则（§4.8.2 优先级：活动 > 分类 > 阶梯 > 基准）
--     ⭐ 费率钳制 1%-20% 由库层 CHECK 强制
-- =====================================================================
INSERT INTO `commission_rule` (`rule_type`,`category_id`,`amount_min`,`amount_max`,`rate`,`priority`,`stack_credit_delta`,`remark`) VALUES
('BASE', NULL, NULL,      NULL,      0.0500, 900, 1, '基准费率 5%'),
('TIER', NULL, 0.00,      500.00,    0.0600, 800, 1, '≤500 元：6%（叠加最低抽成 1 元）'),
('TIER', NULL, 500.00,    2000.00,   0.0500, 800, 1, '500-2000 元：5%'),
('TIER', NULL, 2000.00,   10000.00,  0.0400, 800, 1, '2000-10000 元：4%'),
('TIER', NULL, 10000.00,  99999999.00,0.0300,800, 1, '>10000 元：3%（鼓励大额留在平台）');

-- 分类覆盖费率（示例；category_id 由 V5 在分类插入后回填，此处用 remark 标注）
-- 注意：因分类 id 依赖 V5，分类级费率在 V5 中通过 UPDATE 回填

-- =====================================================================
--  8. 系统参数（§4.8.6 §4.9 §5.6.2 §4.11 §5.8 §5.12）
--     ⛔ 所有阈值必须走这里，禁止硬编码
-- =====================================================================
INSERT INTO `sys_config` (`config_key`,`config_value`,`value_type`,`group_code`,`description`) VALUES
-- 抽成
('commission.base_rate',              '0.05',  'DECIMAL','COMMISSION','基准费率 5%'),
('commission.min_fee',                '1.00',  'DECIMAL','COMMISSION','最低抽成（分账接口按笔计费，小额不能低于渠道成本）'),
('commission.min_rate',               '0.01',  'DECIMAL','COMMISSION','最低费率下限 1%（防多重折扣叠加趋近 0）'),
('commission.max_rate',               '0.20',  'DECIMAL','COMMISSION','最高费率上限 20%（防配置错误导致天价抽成）'),
('commission.tier_enabled',           '1',     'BOOL',   'COMMISSION','是否启用金额阶梯费率'),
('commission.default_stack_credit',   '1',     'BOOL',   'COMMISSION','活动费率默认是否叠加信誉分修正'),
-- 信誉分
('credit.initial_score',              '60',    'INT',    'CREDIT','⭐ 新用户初始信誉分（不是 100 —— 否则"优秀"档形同虚设）'),
('credit.grab_threshold',             '60',    'INT',    'CREDIT','接单门槛分（低于此分不可接单）'),
('credit.daily_add_cap',              '10',    'INT',    'CREDIT','单日加分上限'),
('credit.decay.days1',                '30',    'INT',    'CREDIT','连续无违规 N 天 → 第一次衰减加分'),
('credit.decay.add1',                 '2',     'INT',    'CREDIT','第一次衰减加分值'),
('credit.decay.cap1',                 '70',    'INT',    'CREDIT','衰减加分上限（防止"躺分"回到满分）'),
('credit.decay.days2',                '120',   'INT',    'CREDIT','连续无违规 N 天 → 第二次衰减加分'),
('credit.decay.add2',                 '5',     'INT',    'CREDIT','第二次衰减加分值'),
('credit.appeal_days',                '7',     'INT',    'CREDIT','扣分申诉有效期（天）'),
('credit.manual_adjust_limit',        '20',    'INT',    'CREDIT','信誉分人工调整单次上限（超过需超管复核）'),
('credit.newbie_order_count',         '3',     'INT',    'CREDIT','⭐ 新手单数量（冷启动保护，给固定曝光位）'),
('credit.cross_grab_days',            '30',    'INT',    'CREDIT','互刷检测窗口（天）'),
('credit.cross_grab_times',           '3',     'INT',    'CREDIT','互刷检测阈值（同一对用户互发/互接次数）'),
-- 任务
('task.accept_days',                  '7',     'INT',    'TASK','默认验收期天数（⭐ 超时自动验收，不是自动退回）'),
('task.accept_remind_hours',          '48,24', 'STRING', 'TASK','验收提醒提前小时数'),
('task.modify_round_limit',           '3',     'INT',    'TASK','修改轮次上限（超限自动转争端工单）'),
('task.bid_max_count',                '50',    'INT',    'TASK','单任务最大报价数'),
('task.bid_visible_mode',             'BLIND', 'STRING', 'TASK','报价可见性 BLIND 暗标 / OPEN 明标'),
('task.anti_snipe_enabled',           '1',     'BOOL',   'TASK','竞拍防狙击（最后 N 分钟有新报价则延长）'),
('task.anti_snipe_minutes',           '5',     'INT',    'TASK','防狙击延长窗口与延长时长（分钟）'),
('task.award_decision_days',          '3',     'INT',    'TASK','发单方选定中标方期限（超期自动流标）'),
('task.escrow_pay_timeout_minutes',   '30',    'INT',    'TASK','待托管超时（未支付则取消）'),
('task.high_value_threshold',         '50000', 'DECIMAL','TASK','高价值任务阈值（超过转人工审核）'),
('task.auto_accept_job_batch',        '200',   'INT',    'TASK','自动验收任务单批处理条数'),
-- 争端
('dispute.sla_hours_normal',          '24',    'INT',    'DISPUTE','普通工单 SLA（小时）'),
('dispute.sla_hours_urgent',          '8',     'INT',    'DISPUTE','加急工单 SLA（小时）'),
('dispute.urgent_amount',             '5000',  'DECIMAL','DISPUTE','自动加急金额阈值'),
('dispute.verdict_operator_limit',    '2000',  'DECIMAL','DISPUTE','⭐ 仲裁专员单人权裁决上限（超过需管理员复核）'),
('dispute.verdict_admin_limit',       '10000', 'DECIMAL','DISPUTE','管理员复核上限（超过需三人复核）'),
('dispute.evidence_max_round',        '2',     'INT',    'DISPUTE','最大举证轮次'),
('dispute.appeal_days',               '3',     'INT',    'DISPUTE','申诉期（天）'),
('dispute.assign_timeout_hours',      '4',     'INT',    'DISPUTE','受理超时（自动转下一操作员）'),
-- 提现与资金
('withdraw.min_amount',               '100',   'DECIMAL','WITHDRAW','最低提现金额'),
('withdraw.manual_threshold',         '5000',  'DECIMAL','WITHDRAW','人工审核阈值'),
('withdraw.admin_threshold',          '50000', 'DECIMAL','WITHDRAW','双人复核阈值'),
('withdraw.daily_limit',              '20000', 'DECIMAL','WITHDRAW','单日提现限额'),
('withdraw.fee_rate',                 '0.006', 'DECIMAL','WITHDRAW','提现手续费率'),
('wallet.invariant_check_enabled',    '1',     'BOOL',   'WITHDRAW','⭐ 每日资金不变量校验；不通过即冻结全部提现'),
-- 安全
('security.login_fail_lock_count',    '5',     'INT',    'SECURITY','登录失败锁定阈值'),
('security.login_fail_lock_minutes',  '15',    'INT',    'SECURITY','锁定时长（分钟）'),
('security.sms_hour_limit',           '5',     'INT',    'SECURITY','同号短信小时上限（防验证码轰炸）'),
('security.sms_day_limit',            '10',    'INT',    'SECURITY','同号短信日上限'),
('security.token_access_minutes',     '15',    'INT',    'SECURITY','Access Token 有效期'),
('security.token_refresh_days',       '7',     'INT',    'SECURITY','Refresh Token 有效期（一次性使用）'),
('security.export_max_rows',          '1000',  'INT',    'SECURITY','单次导出最大行数（超过需审批）'),
('security.sensitive_decrypt_alert',  '50',    'INT',    'SECURITY','L4 数据解密次数告警阈值（单人单日）'),
-- 风控
('risk.new_rule_tag_days',            '3',     'INT',    'RISK','⭐ 新规则先以 TAG 模式观察天数（防误杀，R22 对策）'),
('risk.max_rules',                    '200',   'INT',    'RISK','规则数量上限（防不可维护）'),
('risk.device_user_alert',            '3',     'INT',    'RISK','同设备关联账号数告警阈值'),
-- 平台
('platform.name',                     '任务撮合平台','STRING','DEFAULT','平台名称'),
('platform.contact_phone',            '',      'STRING', 'DEFAULT','客服电话'),
('platform.rate_notice_days',         '7',     'INT',    'COMMISSION','费率变更提前公示天数（合规要求）');

-- ===== 2026-10-15 评审定稿参数（§6.1 待确认清单 28 项闭环）=====
INSERT INTO `sys_config` (`config_key`,`config_value`,`value_type`,`group_code`,`description`) VALUES
-- Q8 禁止自交易
('task.self_trade_forbidden',        '1',     'BOOL',   'TASK','禁止同一用户对同一任务既发单又接单（防自买自卖刷单套现）'),
-- Q9 报价可见性：暗标为默认
-- Q21 里程碑 × 多人拆单 Phase 1 互斥（delivery_unit 表已建，Phase 2 放开不必改结算链路）
('task.milestone_multi_exclusive',   '1',     'BOOL',   'TASK','Phase 1 互斥；Phase 2 置 0 即放开'),
-- Q22 里程碑首付款
('task.milestone_prepay_ratio_max',  '0.30',  'DECIMAL','TASK','首个里程碑可设的最高预付比例（降低接单方垫资压力）'),
-- Q24 最低信誉等级：发单方可设但不得低于分类默认
('task.min_credit_level_free',       '0',     'BOOL',   'TASK','0=发单方可设但受分类默认值下限约束；1=完全自由（不推荐）'),
-- Q26 分批结算：Phase 1 只做整体结算
('task.partial_settlement_enabled',  '0',     'BOOL',   'TASK','0=整体结算（Phase 1）；1=分批结算（Phase 2，依赖 delivery_unit）'),
-- Q16 平台认证用户授予标准
('credit.certified.min_orders',      '20',    'INT',    'CREDIT','连续零异常订单数门槛'),
('credit.certified.min_score',       '90',    'INT',    'CREDIT','信誉分门槛'),
('credit.certified.need_approval',   '1',     'BOOL',   'CREDIT','需人工审核 + 超管审批'),
-- Q14 黑名单解封保证金
('credit.blacklist.deposit',         '500',   'DECIMAL','CREDIT','黑名单解封需缴保证金（超管审批后解封）'),
-- Q17 运营活动费率审批
('commission.activity.max_orders',   '2000',  'INT',    'COMMISSION','单次活动覆盖订单数上限'),
('commission.activity.max_budget',   '50000', 'DECIMAL','COMMISSION','单次活动预算上限（费率减免总额）'),
-- Q18 公益类 0% 抽成
('commission.welfare.category_code', 'WELFARE','STRING','COMMISSION','公益求助分类代码；该分类佣金恒为 0，需运营+法务双审'),
('commission.welfare.user_year_limit','3',   'INT',    'COMMISSION','单用户年发布公益任务上限（防被用作刷单/走账通道）'),
-- Q13 个税代扣代缴（需财务/税务最终确认口径）
('wallet.tax.withholding_enabled',   '1',     'BOOL',   'WALLET','平台代扣代缴个人所得税'),
('wallet.tax.income_type',           'LABOR_REMUNERATION','STRING','WALLET','所得类型：劳务报酬所得'),
-- Q28~Q32 安全合规组织与选型
('compliance.mlps_level',            'LEVEL_3','STRING','SECURITY','等保目标等级：三级'),
('compliance.mlps_kickoff',          'W1',    'STRING', 'SECURITY','差距分析启动时点（管理要求占测评项一半以上，必须早启动）'),
('security.waf_mode',                'CLOUD', 'STRING', 'SECURITY','WAF/高防全部买云服务（自建规则维护成本高、效果差）'),
('security.role_separation',         'THREE_WAY','STRING','SECURITY','安全管理员/系统管理员/审计员三权分立；⛔ 审计员不得兼任前两者'),
('risk.external.phone_risk',         '1',     'BOOL',   'RISK','外接手机号风险库（唯一外接数据源）；⛔ 须写入第三方共享清单并做 PIA');

-- ===== 缺陷修复补充参数（P1/P2）=====
INSERT INTO `sys_config` (`config_key`,`config_value`,`value_type`,`group_code`,`description`) VALUES
-- P1-⑤ Refresh Token 宽限期
('security.refresh_grace_seconds',   '30',    'INT',    'SECURITY','⭐ Refresh Token 换发后旧令牌保留的宽限期（秒）—— 防多标签页并发刷新被误踢下线'),
-- P1-⑥ 钱包账户并发策略
('wallet.account_update_mode',       'CONDITIONAL','STRING','WALLET','⭐ 账户余额更新策略：CONDITIONAL 条件更新+行锁（推荐）/ OPTIMISTIC 乐观锁重试'),
('wallet.optimistic_retry_max',      '3',     'INT',    'WALLET','乐观锁模式下的最大重试次数（超过即失败并告警，防重试风暴）'),
-- P1-⑦ 状态权威口径
('order.status_authority',           'TASK',  'STRING', 'TASK','⭐ 状态权威口径：TASK=task.status 为准（展示与流转）/ ORDER=以 biz_order 为准；避免双聚合打架'),
-- P1-⑨ 归档策略
('archive.im_message_keep_days',     '180',   'INT',    'ARCHIVE','IM 消息热表保留天数（超期转归档库）'),
('archive.task_keep_days',           '365',   'INT',    'ARCHIVE','已完成任务热表保留天数'),
('archive.notify_keep_days',         '90',    'INT',    'ARCHIVE','通知记录保留天数'),
('archive.log_keep_days',            '180',   'INT',    'ARCHIVE','登录/风控/访问日志保留天数（等保要求 ≥6 个月）'),
('archive.batch_size',               '5000',  'INT',    'ARCHIVE','单批归档条数（防长事务）'),
-- P1-④ 冗余列一致性巡检
('consistency.check_enabled',        '1',     'BOOL',   'TASK','⭐ 每日巡检冗余列与源数据一致性（task.escrow_status / biz_order.settled_count / sys_user.credit_level）'),
-- P2-⑯ 事件注册与缓存刷新
('event.cache_refresh_topic',        'cache-refresh','STRING','DEFAULT','配置与规则变更的广播 Topic（多实例本地缓存失效）');

-- =====================================================================
--  9. 端能力矩阵（§5.15.2）—— 前端启动时拉取，⛔ 业务代码不得 if(client==MP)
-- =====================================================================
INSERT INTO `sys_client_capability` (`client_code`,`capability`,`supported`,`extra_config`,`min_version`) VALUES
-- Web 用户端
('web-user','PAY_WECHAT',1,'{"mode":"NATIVE"}',''), ('web-user','PAY_ALIPAY',1,'{"mode":"NATIVE"}',''),
('web-user','LARGE_UPLOAD',1,'{"chunked":true,"maxFileSizeMb":2048}',''),
('web-user','PUSH',0,'{}',''), ('web-user','SCAN',0,'{}',''), ('web-user','LOCATION',1,'{"precision":"browser"}',''),
('web-user','BIOMETRIC',0,'{}',''), ('web-user','OFFLINE_DRAFT',0,'{}',''),
-- Web 管理端
('web-admin','PAY_WECHAT',0,'{}',''), ('web-admin','LARGE_UPLOAD',1,'{"chunked":true}',''),
('web-admin','BATCH_OPERATION',1,'{}',''), ('web-admin','LOCATION',0,'{}',''),
-- 微信小程序（⚠️ 三个硬限制）
('mp-weixin','PAY_WECHAT',1,'{"mode":"JSAPI"}',''),
('mp-weixin','PAY_ALIPAY',0,'{"reason":"微信小程序内不允许支付宝支付"}',''),
('mp-weixin','LARGE_UPLOAD',0,'{"maxFileSizeMb":20,"chunked":false,"fallback":"引导至 App/电脑端"}',''),
('mp-weixin','PUSH',1,'{"mode":"SUBSCRIBE_MSG","silent":false,"note":"订阅消息需用户每次主动授权"}',''),
('mp-weixin','SCAN',1,'{}',''), ('mp-weixin','LOCATION',1,'{"needDeclare":true}',''),
('mp-weixin','BIOMETRIC',0,'{}',''), ('mp-weixin','SIMPLIFIED_PUBLISH',1,'{"note":"仅支持一口价+线上/跑腿类（Q27）"}',''),
-- App
('app-android','PAY_WECHAT',1,'{"mode":"SDK"}',''), ('app-android','PAY_ALIPAY',1,'{"mode":"SDK"}',''),
('app-android','LARGE_UPLOAD',1,'{"chunked":true,"background":true}',''),
('app-android','PUSH',1,'{"mode":"VENDOR_PUSH","fallback":"self-ws"}',''),
('app-android','SCAN',1,'{}',''), ('app-android','LOCATION',1,'{}',''), ('app-android','BIOMETRIC',1,'{}',''),
('app-android','OFFLINE_DRAFT',1,'{}',''),
('app-ios','PAY_WECHAT',1,'{"mode":"SDK"}',''), ('app-ios','PAY_ALIPAY',1,'{"mode":"SDK"}',''),
('app-ios','LARGE_UPLOAD',1,'{"chunked":true}',''), ('app-ios','PUSH',1,'{"mode":"APNS"}',''),
('app-ios','SCAN',1,'{}',''), ('app-ios','LOCATION',1,'{}',''), ('app-ios','BIOMETRIC',1,'{"mode":"FaceID"}',''),
-- 桌面端
('desktop','PAY_WECHAT',1,'{"mode":"QRCODE"}',''), ('desktop','PAY_ALIPAY',1,'{"mode":"QRCODE"}',''),
('desktop','LARGE_UPLOAD',1,'{"chunked":true,"maxFileSizeMb":10240}',''),
('desktop','PUSH',1,'{"mode":"SYSTEM_NOTIFY"}',''), ('desktop','MULTI_WINDOW',1,'{}',''),
('desktop','LOGIN_QRCODE',1,'{}','');

-- =====================================================================
--  10. IM 敏感词（§5.8.5 脱离平台交易识别，含变体）
-- =====================================================================
INSERT INTO `im_sensitive_word` (`word`,`category`,`action`) VALUES
('微信','OFF_PLATFORM','WARN'), ('威信','OFF_PLATFORM','WARN'), ('V信','OFF_PLATFORM','WARN'), ('vx','OFF_PLATFORM','WARN'),
('加我','OFF_PLATFORM','WARN'), ('私聊','OFF_PLATFORM','WARN'), ('线下交易','OFF_PLATFORM','BLOCK'),
('QQ','OFF_PLATFORM','WARN'), ('手机号','OFF_PLATFORM','WARN'), ('扫码加','OFF_PLATFORM','WARN'),
('刷单','FRAUD','BLOCK'), ('打字员','FRAUD','BLOCK'), ('点赞兼职','FRAUD','BLOCK'), ('日结高薪','FRAUD','WARN'),
('押金','FRAUD','BLOCK'), ('保证金先交','FRAUD','BLOCK'), ('培训费','FRAUD','BLOCK'), ('垫付','FRAUD','WARN');

-- =====================================================================
--  11. 通知模板（关键交易节点；⛔ 交易类通知不可被用户关闭）
-- =====================================================================
INSERT INTO `notify_template` (`tpl_code`,`channel`,`title`,`content`,`ext_config`) VALUES
('TASK_AWARDED','INNER','恭喜中标','你的报价已被采纳，任务「${taskTitle}」请在 ${deadline} 前完成交付。',NULL),
('TASK_AWARDED','SMS','【任务撮合平台】恭喜中标','任务「${taskTitle}」已中标，请及时交付。',NULL),
('TASK_AWARD_REJECTED','INNER','很遗憾未中标','任务「${taskTitle}」已选定其他接单方，看看相似任务？',NULL),
('GRAB_SUCCESS','INNER','抢单成功','你已抢到「${taskTitle}」，请按时履约。',NULL),
('GRAB_FAILED','INNER','手慢了一步','「${taskTitle}」已被他人抢走，为你推荐了相似任务。',NULL),
('DELIVERY_SUBMITTED','INNER','对方已交付','「${taskTitle}」已提交交付物，请在 ${acceptDeadline} 前验收。',NULL),
('ACCEPT_REMIND_48H','INNER','验收提醒','「${taskTitle}」还有 2 天将自动验收。',NULL),
('ACCEPT_REMIND_24H','INNER','验收提醒（最后 1 天）','「${taskTitle}」明天将自动验收并结算。',NULL),
('AUTO_ACCEPTED','INNER','已自动验收','「${taskTitle}」超期未操作，系统已自动验收并结算。',NULL),
('SUB_ORDER_SETTLED','INNER','款项已到账','「${taskTitle}」已结算，实得 ¥${netAmount}（服务费 ¥${commission}）。',NULL),
('DELIVERY_REJECTED','INNER','交付被驳回','「${taskTitle}」被驳回，原因：${reason}。剩余修改次数 ${leftRounds}。',NULL),
('DISPUTE_OPENED','INNER','争端已受理','工单 ${ticketNo} 已受理，请在 ${evidenceDeadline} 前补充证据。',NULL),
('DISPUTE_VERDICT','INNER','裁决结果','工单 ${ticketNo} 已裁决：${resultText}。理由：${reason}',NULL),
('CREDIT_CHANGED','INNER','信誉分变动','你的信誉分 ${deltaText}（${scoreBefore} → ${scoreAfter}），原因：${reason}',NULL),
('WITHDRAW_RESULT','INNER','提现结果','提现 ¥${amount} ${statusText}。',NULL);

-- =====================================================================
--  校验：种子数据自检（执行后应无输出；有输出说明数据不自洽）
-- =====================================================================
-- ① 每个平台身份至少有一个角色
SELECT 'CHECK_FAIL: identity without role' AS msg, identity FROM sys_role GROUP BY identity HAVING COUNT(*) = 0;
-- ② 互斥岗位必须存在
SELECT 'CHECK_FAIL: conflict role not exists' AS msg, c.role_code_a
  FROM sys_role_conflict c LEFT JOIN sys_role r ON r.role_code = c.role_code_a WHERE r.id IS NULL;
SELECT 'CHECK_FAIL: conflict role not exists' AS msg, c.role_code_b
  FROM sys_role_conflict c LEFT JOIN sys_role r ON r.role_code = c.role_code_b WHERE r.id IS NULL;
-- ③ 信誉等级区间必须连续覆盖 0-100 且不重叠
SELECT 'CHECK_FAIL: credit level gap or overlap' AS msg FROM (
  SELECT min_score, max_score, LAG(max_score) OVER (ORDER BY min_score) AS prev_max
    FROM credit_level WHERE enabled = 1 AND award_mode = 'AUTO'
) t WHERE prev_max IS NOT NULL AND prev_max + 1 <> min_score;
-- ④ 费率规则必须命中 BASE 兜底（否则会"未命中费率"）
SELECT 'CHECK_FAIL: no BASE commission rule' AS msg FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM commission_rule WHERE rule_type='BASE' AND enabled=1);
