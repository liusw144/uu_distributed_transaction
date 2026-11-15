CREATE TABLE IF NOT EXISTS seata_tcc_account
(
    user_id
    VARCHAR
(
    40
) PRIMARY KEY, available DECIMAL
(
    19,
    2
) NOT NULL, frozen DECIMAL
(
    19,
    2
) NOT NULL,
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
CREATE TABLE IF NOT EXISTS seata_tcc_account_branch
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
INSERT INTO seata_tcc_account(user_id, available, frozen)
VALUES ('u100', 10000, 0) ON CONFLICT DO NOTHING;
