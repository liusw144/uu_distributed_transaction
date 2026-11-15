-- 在专用演示库 dtx_demo_mysql 执行。重复执行保留现有订单和库存。
CREATE TABLE IF NOT EXISTS demo_orders
(
    order_id
    VARCHAR
(
    64
) PRIMARY KEY COMMENT '调用方生成的业务编号，重复提交返回冲突',
    scenario VARCHAR
(
    16
) NOT NULL,user_id VARCHAR
(
    40
) NOT NULL,product_id VARCHAR
(
    40
) NOT NULL,
    quantity INT NOT NULL,amount DECIMAL
(
    18,
    2
) NOT NULL,status VARCHAR
(
    20
) NOT NULL,
    created_at TIMESTAMP
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS demo_stock
(
    id
    VARCHAR
(
    80
) PRIMARY KEY,available INT NOT NULL, CHECK
(
    available
    >=
    0
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT
IGNORE INTO demo_stock(id,available) VALUES ('SEATA_XA:SKU-001',100);

