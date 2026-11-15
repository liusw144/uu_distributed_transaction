-- 在专用演示库 dtx_demo_pg 执行，重复执行不重置余额。
CREATE TABLE IF NOT EXISTS demo_accounts
(
    id
    VARCHAR
(
    80
) PRIMARY KEY,balance NUMERIC
(
    18,
    2
) NOT NULL CHECK
(
    balance
    >=
    0
)
    );
INSERT INTO demo_accounts(id, balance)
VALUES ('SEATA_XA:U1001', 10000.00) ON CONFLICT(id) DO NOTHING;

