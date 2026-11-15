-- Copyright 2013-2023 the original author or authors.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     https://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- 本机学习适配：基于 Spring Cloud Alibaba 2023.0.3.4 integrated-example。
-- 本文件提供完整本机默认值，直接运行 main；环境变量覆盖是可选项。

-- 统一初始化：只创建独立演示库/表，并为不存在的资源补种子。
-- 重复执行不会恢复余额、库存或点赞数；不会清空或覆盖已有业务数据。
-- 使用 MySQL 8.0.16+ 以执行 CHECK 约束，默认连接为 127.0.0.1:3308 root/root。
-- 先查询实际基线再演示：新库 admin 余额 3、商品 1 库存 100、单价 2（整数教学单位）。

CREATE
DATABASE IF NOT EXISTS `sca_integrated_storage` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE
DATABASE IF NOT EXISTS `sca_integrated_account` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE
DATABASE IF NOT EXISTS `sca_integrated_order` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE
DATABASE IF NOT EXISTS `sca_integrated_praise` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- 库存分支：商品编号唯一，条件扣减与数据库约束共同防止负库存。
USE
`sca_integrated_storage`;
CREATE TABLE IF NOT EXISTS `storage`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `commodity_code`
    VARCHAR
(
    255
) NOT NULL,
    `count` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
),
    `update_time` DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
) ON UPDATE CURRENT_TIMESTAMP
(
    6
),
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_storage_commodity_code`
(
    `commodity_code`
),
    CONSTRAINT `ck_storage_count_nonnegative` CHECK
(
    `count`
    >=
    0
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

-- 账户分支：用户标识唯一，金额使用整数教学单位。
USE
`sca_integrated_account`;
CREATE TABLE IF NOT EXISTS `account`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `user_id`
    VARCHAR
(
    255
) NOT NULL,
    `money` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
),
    `update_time` DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
) ON UPDATE CURRENT_TIMESTAMP
(
    6
),
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_account_user_id`
(
    `user_id`
),
    CONSTRAINT `ck_account_money_nonnegative` CHECK
(
    `money`
    >=
    0
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

-- 订单记录：count 是实际持久化字段；响应中的 xid 不写入订单表。
USE
`sca_integrated_order`;
CREATE TABLE IF NOT EXISTS `order`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `user_id`
    VARCHAR
(
    255
) NOT NULL,
    `commodity_code` VARCHAR
(
    255
) NOT NULL,
    `count` INT NOT NULL,
    `money` INT NOT NULL,
    `create_time` DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
),
    `update_time` DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
) ON UPDATE CURRENT_TIMESTAMP
(
    6
),
    PRIMARY KEY
(
    `id`
),
    KEY `ix_order_user_id`
(
    `user_id`
),
    CONSTRAINT `ck_order_count_positive` CHECK
(
    `count` >
    0
),
    CONSTRAINT `ck_order_money_nonnegative` CHECK
(
    `money`
    >=
    0
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

-- 异步点赞使用独立库，不加入订单同步全局事务。
USE
`sca_integrated_praise`;
CREATE TABLE IF NOT EXISTS `item`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `praise`
    INT
    NOT
    NULL
    DEFAULT
    0,
    `create_time`
    DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
),
    `update_time` DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
) ON UPDATE CURRENT_TIMESTAMP
(
    6
),
    PRIMARY KEY
(
    `id`
),
    CONSTRAINT `ck_item_praise_nonnegative` CHECK
(
    `praise`
    >=
    0
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

-- 三个 AT 事务库各有自己的 Seata 2.0 MySQL undo_log。
-- xid + branch_id 唯一；rollback_info 保存分支撤销数据，勿手工伪造或清理活动日志。
USE
`sca_integrated_storage`;
CREATE TABLE IF NOT EXISTS `undo_log`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `branch_id`
    BIGINT
    NOT
    NULL,
    `xid`
    VARCHAR
(
    128
) NOT NULL,
    `context` VARCHAR
(
    128
) NOT NULL,
    `rollback_info` LONGBLOB NOT NULL,
    `log_status` INT NOT NULL,
    `log_created` DATETIME
(
    6
) NOT NULL,
    `log_modified` DATETIME
(
    6
) NOT NULL,
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `ux_undo_log`
(
    `xid`,
    `branch_id`
),
    KEY `ix_undo_log_created`
(
    `log_created`
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

USE
`sca_integrated_account`;
CREATE TABLE IF NOT EXISTS `undo_log`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `branch_id`
    BIGINT
    NOT
    NULL,
    `xid`
    VARCHAR
(
    128
) NOT NULL,
    `context` VARCHAR
(
    128
) NOT NULL,
    `rollback_info` LONGBLOB NOT NULL,
    `log_status` INT NOT NULL,
    `log_created` DATETIME
(
    6
) NOT NULL,
    `log_modified` DATETIME
(
    6
) NOT NULL,
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `ux_undo_log`
(
    `xid`,
    `branch_id`
),
    KEY `ix_undo_log_created`
(
    `log_created`
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

USE
`sca_integrated_order`;
CREATE TABLE IF NOT EXISTS `undo_log`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `branch_id`
    BIGINT
    NOT
    NULL,
    `xid`
    VARCHAR
(
    128
) NOT NULL,
    `context` VARCHAR
(
    128
) NOT NULL,
    `rollback_info` LONGBLOB NOT NULL,
    `log_status` INT NOT NULL,
    `log_created` DATETIME
(
    6
) NOT NULL,
    `log_modified` DATETIME
(
    6
) NOT NULL,
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `ux_undo_log`
(
    `xid`,
    `branch_id`
),
    KEY `ix_undo_log_created`
(
    `log_created`
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

-- 只插入不存在的种子行；已存在的余额、库存、点赞数均原样保留。
INSERT INTO `sca_integrated_storage`.`storage` (`commodity_code`, `count`, `create_time`, `update_time`)
SELECT seed.`commodity_code`, 100, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM (SELECT '1' AS `commodity_code`) AS seed
         LEFT JOIN `sca_integrated_storage`.`storage` AS existing_row
                   ON existing_row.`commodity_code` = seed.`commodity_code`
WHERE existing_row.`id` IS NULL;

INSERT INTO `sca_integrated_account`.`account` (`user_id`, `money`, `create_time`, `update_time`)
SELECT seed.`user_id`, 3, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM (SELECT 'admin' AS `user_id`) AS seed
         LEFT JOIN `sca_integrated_account`.`account` AS existing_row
                   ON existing_row.`user_id` = seed.`user_id`
WHERE existing_row.`id` IS NULL;

INSERT INTO `sca_integrated_praise`.`item` (`id`, `praise`, `create_time`, `update_time`)
SELECT seed.`id`, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM (SELECT 1 AS `id`) AS seed
         LEFT JOIN `sca_integrated_praise`.`item` AS existing_row
                   ON existing_row.`id` = seed.`id`
WHERE existing_row.`id` IS NULL;
