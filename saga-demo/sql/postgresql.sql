-- 仅在 dtx_demo_pg 专用库执行；初始化不会覆盖已扣减的余额。
CREATE TABLE IF NOT EXISTS saga_account_actions
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
) NOT NULL CHECK
(
    state
    IN
(
    'NEW',
    'APPLIED',
    'COMPENSATED'
))
    );
INSERT INTO demo_accounts(id, balance)
VALUES ('SAGA:U1001', 10000.00) ON CONFLICT(id) DO NOTHING;
