-- 在 dtx_demo_pg 中执行；重复初始化不重置现有资源。
CREATE TABLE IF NOT EXISTS tcc_accounts
(
    user_id
    VARCHAR
(
    40
) PRIMARY KEY,
    available NUMERIC
(
    18,
    2
) NOT NULL,
    reserved NUMERIC
(
    18,
    2
) NOT NULL DEFAULT 0,
    confirmed NUMERIC
(
    18,
    2
) NOT NULL DEFAULT 0,
    CONSTRAINT tcc_account_nonnegative CHECK
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
    );
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
    CONSTRAINT tcc_account_branch_state CHECK
(
    state
    IN
(
    'NEW',
    'RESERVED',
    'CONFIRMED',
    'CANCELLED'
))
    );
INSERT INTO tcc_accounts(user_id, available, reserved, confirmed)
VALUES ('U1001', 10000.00, 0, 0) ON CONFLICT(user_id) DO NOTHING;
