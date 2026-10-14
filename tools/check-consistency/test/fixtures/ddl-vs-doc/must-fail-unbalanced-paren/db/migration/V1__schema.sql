-- 反向验证夹具 1/2：DDL 括号不配平（解析失败必须硬错误）
CREATE TABLE `escrow` (
  `id`              BIGINT        NOT NULL,
  `released_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  PRIMARY KEY (`id`)
ENGINE=InnoDB;
