CREATE TABLE `escrow` (
  `id` BIGINT NOT NULL,
  `released_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;