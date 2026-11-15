-- 在 dtx_demo_mysql 中执行；重复初始化不重置现有资源。
CREATE TABLE IF NOT EXISTS tcc_stock
(
    product_id
    VARCHAR
(
    40
) PRIMARY KEY,
    available INT NOT NULL,
    reserved INT NOT NULL DEFAULT 0,
    confirmed INT NOT NULL DEFAULT 0,
    CONSTRAINT tcc_stock_nonnegative CHECK
(
    available
    >=
    0
    AND
    reserved
    >=
    0
    AND
    confirmed
    >=
    0
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tcc_branches
(
    tx_id
    VARCHAR
(
    64
) PRIMARY KEY,
    payload TEXT NOT NULL,
    state VARCHAR
(
    16
) NOT NULL,
    CONSTRAINT tcc_stock_branch_state CHECK
(
    state
    IN
(
    'NEW',
    'RESERVED',
    'CONFIRMED',
    'CANCELLED'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT
IGNORE INTO tcc_stock(product_id,available,reserved,confirmed) VALUES ('SKU-001',100,0,0);
