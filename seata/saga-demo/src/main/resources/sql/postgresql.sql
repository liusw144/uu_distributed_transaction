CREATE TABLE IF NOT EXISTS seata_saga_account
(
    id
    VARCHAR
(
    40
) PRIMARY KEY, balance NUMERIC
(
    18,
    2
) NOT NULL);
CREATE TABLE IF NOT EXISTS seata_saga_account_actions
(
    tx_id
    VARCHAR
(
    64
) PRIMARY KEY, payload TEXT NOT NULL, state VARCHAR
(
    20
) NOT NULL);
INSERT INTO seata_saga_account(id, balance)
VALUES ('u1', 1000.00) ON CONFLICT(id) DO NOTHING;
