-- 正向夹具（新能力，必须 0 违规）：
--   ① CREATE TABLE 里带 `COMMENT 'rate--flag'` —— 字符串里的 `--` ⛔ 不能被当注释切掉
--      （旧实现无脑 replace(/--.*$/) 会把引号切一半 → 括号/引号配对失败 → 整表消失）
--   ② 新增列走 `ALTER TABLE ... ADD COLUMN` —— 前向迁移的标准写法，
--      解析器必须认，否则第二次"加一列"就会让门禁对【合法的新列】误报
CREATE TABLE `task` (
  `id`    BIGINT       NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(128) NOT NULL COMMENT '标题（注释里带 -- 也是合法的：rate--flag）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;

ALTER TABLE `task`
  ADD COLUMN `escrow_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '托管金额',
  ADD COLUMN `escrow_status` VARCHAR(16)   NOT NULL DEFAULT 'NONE';
