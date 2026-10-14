-- =====================================================================
--  任务撮合平台 · 种子数据 V5：任务分类树 + 分类级费率回填
--  对应设计：§4.10.2 业务分类（维度一）§4.8.4 分类覆盖费率
--  注意：V4 已插入 BASE/TIER 费率规则；分类级费率依赖分类 id，故在本文件回填
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 一级分类
-- ---------------------------------------------------------------------
INSERT INTO `task_category`
(`parent_id`,`path`,`level`,`code`,`name`,`icon`,`allow_forms`,`allow_pay_modes`,`require_license`,`audit_level`,`need_checkin_default`,`sort_no`) VALUES
(0,'0',            1,'DEV',      '软件开发','💻','ONLINE,HYBRID',              'FIXED,BID,MILESTONE',              0,'MACHINE',0, 10),
(0,'0',            1,'DESIGN',   '设计创意','🎨','ONLINE,HYBRID',              'FIXED,BID,BY_QUANTITY,MILESTONE',  0,'MACHINE',0, 20),
(0,'0',            1,'COPY',     '文案写作','✍️','ONLINE',                     'FIXED,BID,BY_QUANTITY',            0,'MACHINE',0, 30),
(0,'0',            1,'DATA',     '数据处理','📊','ONLINE',                     'FIXED,BID,BY_QUANTITY',            0,'MACHINE',0, 40),
(0,'0',            1,'AV',       '音视频制作','🎬','ONLINE,HYBRID',            'FIXED,BID,BY_QUANTITY',            0,'MACHINE',0, 50),
(0,'0',            1,'MARKET',   '市场营销','📈','HYBRID,OFFLINE',             'FIXED,BID,BY_DAY,MILESTONE',       0,'HUMAN',  0, 60),
(0,'0',            1,'LIFE',     '生活服务','🔧','OFFLINE',                    'FIXED,BID,BY_DAY',                 1,'HUMAN',  1, 70),
(0,'0',            1,'ERRAND',   '跑腿代办','🏃','OFFLINE',                    'FIXED',                            0,'MACHINE',1, 80),
(0,'0',            1,'EDU',      '教育培训','📚','OFFLINE,HYBRID',             'FIXED,BID,BY_DAY',                 0,'HUMAN',  1, 90),
(0,'0',            1,'OTHER',    '其他',    '📦','ONLINE,OFFLINE,HYBRID',      'FIXED,BID,BY_QUANTITY,BY_DAY,MILESTONE',0,'HUMAN',0,999),
(0,'0',            1,'WELFARE',  '公益求助','🤝','ONLINE,OFFLINE,HYBRID',      'FIXED,BID',                        0,'HUMAN',0,998);

-- 回填一级分类 path（父为 0 时 path = 自身 id 的字符串形式）
UPDATE `task_category` SET `path` = CAST(`id` AS CHAR) WHERE `parent_id` = 0;

-- ---------------------------------------------------------------------
-- 2. 二级分类（path 由父 path + '/' + 自身 id 生成）
-- ---------------------------------------------------------------------
INSERT INTO `task_category`
(`parent_id`,`path`,`level`,`code`,`name`,`allow_forms`,`allow_pay_modes`,`require_license`,`audit_level`,`need_checkin_default`,`sort_no`)
SELECT p.id, CONCAT(p.path,'/0'), 2, x.code, x.name, p.allow_forms, p.allow_pay_modes, 0, p.audit_level, p.need_checkin_default, x.sort_no
FROM `task_category` p
JOIN (
  SELECT 'DEV'    AS pcode, 'DEV_BACKEND'  AS code, '后端开发'    AS name, 10 AS sort_no UNION ALL
  SELECT 'DEV',    'DEV_FRONTEND', '前端开发',    20 UNION ALL
  SELECT 'DEV',    'DEV_MOBILE',   '移动端开发',  30 UNION ALL
  SELECT 'DEV',    'DEV_MINIAPP',  '小程序开发',  40 UNION ALL
  SELECT 'DEV',    'DEV_TEST',     '测试',        50 UNION ALL
  SELECT 'DEV',    'DEV_OPS',      '运维部署',    60 UNION ALL
  SELECT 'DEV',    'DEV_ALGO',     '算法/AI',     70 UNION ALL
  SELECT 'DESIGN', 'DESIGN_UI',    'UI 设计',     10 UNION ALL
  SELECT 'DESIGN', 'DESIGN_GRAPHIC','平面设计',   20 UNION ALL
  SELECT 'DESIGN', 'DESIGN_3D',    '3D 建模/效果图',30 UNION ALL
  SELECT 'DESIGN', 'DESIGN_BRAND', '品牌 VI',     40 UNION ALL
  SELECT 'COPY',   'COPY_ARTICLE', '文章撰写',    10 UNION ALL
  SELECT 'COPY',   'COPY_SCRIPT',  '脚本策划',    20 UNION ALL
  SELECT 'COPY',   'COPY_TRANS',   '翻译',        30 UNION ALL
  SELECT 'COPY',   'COPY_PROOF',   '校对润色',    40 UNION ALL
  SELECT 'DATA',   'DATA_LABEL',   '数据标注',    10 UNION ALL
  SELECT 'DATA',   'DATA_COLLECT', '数据采集',    20 UNION ALL
  SELECT 'DATA',   'DATA_CLEAN',   '数据清洗',    30 UNION ALL
  SELECT 'DATA',   'DATA_ENTRY',   '数据录入',    40 UNION ALL
  SELECT 'AV',     'AV_EDIT',      '视频剪辑',    10 UNION ALL
  SELECT 'AV',     'AV_VOICE',     '配音',        20 UNION ALL
  SELECT 'AV',     'AV_ANIM',      '动画制作',    30 UNION ALL
  SELECT 'MARKET', 'MKT_PROMOTE',  '推广投放',    10 UNION ALL
  SELECT 'MARKET', 'MKT_EVENT',    '活动执行',    20 UNION ALL
  SELECT 'LIFE',   'LIFE_HOUSE',   '家政保洁',    10 UNION ALL
  SELECT 'LIFE',   'LIFE_REPAIR',  '维修安装',    20 UNION ALL
  SELECT 'LIFE',   'LIFE_PHOTO',   '摄影摄像',    30 UNION ALL
  SELECT 'ERRAND', 'ER_BUY',       '代买',        10 UNION ALL
  SELECT 'ERRAND', 'ER_SEND',      '代送',        20 UNION ALL
  SELECT 'ERRAND', 'ER_QUEUE',     '排队代办',    30 UNION ALL
  SELECT 'EDU',    'EDU_TUTOR',    '家教辅导',    10 UNION ALL
  SELECT 'EDU',    'EDU_TRAIN',    '陪练',        20 UNION ALL
  SELECT 'OTHER',  'OTHER_MISC',   '其他任务',    10 UNION ALL
  SELECT 'WELFARE','WELFARE_AID',  '紧急求助',    10 UNION ALL
  SELECT 'WELFARE','WELFARE_PUBLIC','公益活动',    20
) x ON x.pcode = p.code
WHERE p.level = 1;

-- 回填二级 path = 父 path + '/' + 自身 id
UPDATE `task_category` c
  JOIN `task_category` p ON p.id = c.parent_id
   SET c.path = CONCAT(p.path, '/', c.id)
 WHERE c.level = 2;

-- ---------------------------------------------------------------------
-- 3. 分类覆盖费率（§4.8.4）
--    优先级：CATEGORY(priority 100) > TIER(800) > BASE(900)
-- ---------------------------------------------------------------------
INSERT INTO `commission_rule` (`rule_type`,`category_id`,`rate`,`priority`,`stack_credit_delta`,`remark`)
SELECT 'CATEGORY', c.id, r.rate, 100, 1, r.remark
FROM `task_category` c
JOIN (
  SELECT 'DEV'    AS code, 0.0500 AS rate, '软件开发 5%（基准）'         AS remark UNION ALL
  SELECT 'DESIGN', 0.0500, '设计创意 5%（基准）' UNION ALL
  SELECT 'COPY',   0.0800, '文案写作 8%（小额高频，需覆盖渠道与风控成本）' UNION ALL
  SELECT 'DATA',   0.0800, '数据处理 8%（小额高频）' UNION ALL
  SELECT 'AV',     0.0600, '音视频制作 6%' UNION ALL
  SELECT 'MARKET', 0.0600, '市场营销 6%' UNION ALL
  SELECT 'LIFE',   0.1000, '生活服务 10%（履约风险高、平台介入多）' UNION ALL
  SELECT 'ERRAND', 0.1000, '跑腿代办 10%（履约风险高）' UNION ALL
  SELECT 'EDU',    0.0800, '教育培训 8%' UNION ALL
  SELECT 'OTHER',  0.0600, '其他 6%'
) r ON r.code = c.code
WHERE c.level = 1 AND c.code <> 'WELFARE';   -- ⭐ 公益类不走费率规则，服务层按 WELFARE_FREE 直接返回佣金 0（Q18）

-- ---------------------------------------------------------------------
-- 4. 公益/求助类 0% 抽成（§4.8.4；需运营 + 法务双审，Q18）
--    用独立分类承载，费率 0（注意 CHECK 约束下限是 0.01，故此处禁用该规则，
--    实际以 ACTIVITY 类型的全额减免实现；见下方说明）
-- ---------------------------------------------------------------------
-- ⚠️ commission_rule.rate 的 CHECK 约束为 [0.01, 0.20]，无法直接存 0。
--    公益类 0 抽成通过 ACTIVITY 规则 + 服务层「全额减免」开关实现：
--    在 commission_rule 增加一条 rate=0.01、activity 覆盖为"全免"的规则不可行，
--    因此落地方式为：category.require_license + 服务层 PUBLIC_WELFARE 白名单直接返回佣金 0，
--    并在 commission_detail.rule_snapshot 中记录 reason=PUBLIC_WELFARE_FREE。
--    该分支必须由运营管理员 + 法务双审（见 Q18）。

-- ---------------------------------------------------------------------
-- 5. 校验
-- ---------------------------------------------------------------------
-- 每个一级分类都必须有 ≥1 个二级分类
SELECT 'CHECK_FAIL: level1 category without children' AS msg, p.code
  FROM `task_category` p
 WHERE p.level = 1
   AND NOT EXISTS (SELECT 1 FROM `task_category` c WHERE c.parent_id = p.id);

-- 分类级费率不得低于 1%（库层已挡，这里再自查一次业务合理性）
SELECT 'WARN: category rate below 2%' AS msg, c.name, r.rate
  FROM `commission_rule` r JOIN `task_category` c ON c.id = r.category_id
 WHERE r.rule_type='CATEGORY' AND r.rate < 0.02;
