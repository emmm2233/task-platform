-- 反向验证夹具 2/2：DDL 完全正常，但文档里【一条 `表.列` 引用都没有】。
-- 触发方式都是真实写法：把引用放进 ``` 代码块、去掉反引号、改成大写、
-- 或者 extraDocFiles 配置丢了。
-- 期望：门禁必须报 nothing-checked（"这道门禁没在工作"），
--       ⛔ 不能因为 refs 为空就零违规绿灯。
CREATE TABLE `escrow` (
  `id`              BIGINT        NOT NULL,
  `released_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;
