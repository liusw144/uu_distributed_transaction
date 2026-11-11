-- 仅在 dtx_demo_mysql 专用库执行；初始化不会覆盖已扣减的库存。
CREATE TABLE IF NOT EXISTS saga_stock_actions
(
    tx_id
    VARCHAR
(
    64
) PRIMARY KEY COMMENT '订单号，也是分支幂等键',
    payload TEXT NOT NULL,
    state VARCHAR
(
    16
) NOT NULL,
    CONSTRAINT saga_stock_action_state CHECK
(
    state
    IN
(
    'NEW',
    'APPLIED',
    'COMPENSATED'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO demo_stock(id, available)
VALUES ('SAGA:SKU-001', 100) ON DUPLICATE KEY
UPDATE id=id;
