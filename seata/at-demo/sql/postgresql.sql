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
-- Seata 2.2 PostgreSQL 适配器需要 undo_log_id_seq，不能直接使用 MySQL 建表语句。
CREATE TABLE IF NOT EXISTS undo_log
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    branch_id
    BIGINT
    NOT
    NULL,
    xid
    VARCHAR
(
    128
) NOT NULL,
    context VARCHAR
(
    128
) NOT NULL,rollback_info BYTEA NOT NULL,log_status INT NOT NULL,
    log_created TIMESTAMP
(
    6
) NOT NULL,log_modified TIMESTAMP
(
    6
) NOT NULL,
    CONSTRAINT ux_undo_log UNIQUE
(
    xid,
    branch_id
)
    );
ALTER TABLE undo_log
    ADD COLUMN IF NOT EXISTS id BIGSERIAL;
INSERT INTO demo_accounts(id, balance)
VALUES ('SEATA_AT:U1001', 10000.00) ON CONFLICT(id) DO NOTHING;

