CREATE TABLE `task` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `task_no`    VARCHAR(32)  NOT NULL,
  `title`      VARCHAR(128) NOT NULL,
  `escrow_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;