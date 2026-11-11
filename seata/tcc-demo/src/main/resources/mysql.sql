CREATE TABLE IF NOT EXISTS seata_tcc_stock
(
    product_id
    VARCHAR
(
    40
) PRIMARY KEY, available INT NOT NULL, frozen INT NOT NULL,
    CHECK
(
    available
    >=
    0
), CHECK
(
    frozen
    >=
    0
)
    );
CREATE TABLE IF NOT EXISTS seata_tcc_order_branch
(
    xid
    VARCHAR
(
    128
) NOT NULL, branch_id BIGINT NOT NULL, state VARCHAR
(
    16
) NOT NULL,
    order_id VARCHAR
(
    64
) UNIQUE, user_id VARCHAR
(
    40
), product_id VARCHAR
(
    40
), quantity INT,
    amount DECIMAL
(
    19,
    2
), unit_price DECIMAL
(
    19,
    2
), PRIMARY KEY
(
    xid,
    branch_id
)
    );
CREATE TABLE IF NOT EXISTS seata_tcc_orders
(
    order_id
    VARCHAR
(
    64
) PRIMARY KEY, user_id VARCHAR
(
    40
) NOT NULL, product_id VARCHAR
(
    40
) NOT NULL,
    quantity INT NOT NULL, unit_price DECIMAL
(
    19,
    2
) NOT NULL, amount DECIMAL
(
    19,
    2
) NOT NULL,
    state VARCHAR
(
    16
) NOT NULL, xid VARCHAR
(
    128
) NOT NULL, branch_id BIGINT NOT NULL
    );
INSERT
IGNORE INTO seata_tcc_stock(product_id,available,frozen) VALUES ('p100',100,0);
