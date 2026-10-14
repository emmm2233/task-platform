-- ⛔ 反向验证夹具：CREATE TABLE 的括号【不配平】（少一个右括号）。
--
-- 期望：门禁必须【响亮地失败】。
-- 历史缺陷：解析器遇到配不上对的括号时是 `continue`（静默跳过整张表），
-- 于是这张表从五道门禁里整体消失 —— 没东西可校验 ⇒ 零违规 ⇒ 绿灯。
-- 实测：只注入一个不存在的属性时门禁报错；同一个注入 + 本文件这种括号不配平
--       就变成 0 违规，缺陷被"解析失败"藏了起来。
CREATE TABLE `task` (
  `id`    BIGINT       NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(128) NOT NULL,
  PRIMARY KEY (`id`)
ENGINE=InnoDB;
