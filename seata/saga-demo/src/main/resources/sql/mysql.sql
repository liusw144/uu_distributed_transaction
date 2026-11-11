-- 仅创建本示例专用表；绝不覆盖已有资源。
CREATE TABLE IF NOT EXISTS seata_saga_stock
(
    id
    VARCHAR
(
    40
) PRIMARY KEY, available INT NOT NULL);
CREATE TABLE IF NOT EXISTS seata_saga_order_actions
(
    tx_id
    VARCHAR
(
    64
) PRIMARY KEY, payload TEXT NOT NULL, state VARCHAR
(
    20
) NOT NULL);
CREATE TABLE IF NOT EXISTS seata_saga_stock_actions
(
    tx_id
    VARCHAR
(
    64
) PRIMARY KEY, payload TEXT NOT NULL, state VARCHAR
(
    20
) NOT NULL);
CREATE TABLE IF NOT EXISTS seata_saga_requests
(
    order_id
    VARCHAR
(
    64
) PRIMARY KEY, payload TEXT NOT NULL);
INSERT INTO seata_saga_stock(id, available)
VALUES ('p1', 100) ON DUPLICATE KEY
UPDATE id=id;
-- 与 Apache Seata 2.0.0 StateLangStoreSqls / StateLogStoreSqls 字段兼容；时间保留毫秒用于乐观锁。
CREATE TABLE IF NOT EXISTS seata_saga_state_machine_def
(
    id
    VARCHAR
(
    128
) PRIMARY KEY, tenant_id VARCHAR
(
    128
), app_name VARCHAR
(
    128
), name VARCHAR
(
    128
),
    status VARCHAR
(
    2
), gmt_create DATETIME
(
    3
), ver VARCHAR
(
    32
), type VARCHAR
(
    20
), content LONGTEXT,
    recover_strategy VARCHAR
(
    32
), comment_ VARCHAR
(
    255
), UNIQUE KEY saga_def_version
(
    name,
    tenant_id,
    ver
)
    );
CREATE TABLE IF NOT EXISTS seata_saga_state_machine_inst
(
    id
    VARCHAR
(
    128
) PRIMARY KEY, machine_id VARCHAR
(
    128
), tenant_id VARCHAR
(
    128
), parent_id VARCHAR
(
    128
),
    business_key VARCHAR
(
    128
), gmt_started DATETIME
(
    3
), gmt_end DATETIME
(
    3
), status VARCHAR
(
    2
),
    compensation_status VARCHAR
(
    2
), is_running TINYINT, gmt_updated DATETIME
(
    3
),
    start_params LONGTEXT, end_params LONGTEXT, excep LONGBLOB,
    UNIQUE KEY saga_business_key
(
    tenant_id,
    business_key
)
    );
CREATE TABLE IF NOT EXISTS seata_saga_state_inst
(
    id
    VARCHAR
(
    128
), machine_inst_id VARCHAR
(
    128
), name VARCHAR
(
    128
), type VARCHAR
(
    32
), business_key VARCHAR
(
    128
),
    gmt_started DATETIME
(
    3
), service_name VARCHAR
(
    128
), service_method VARCHAR
(
    128
), service_type VARCHAR
(
    32
),
    is_for_update TINYINT, status VARCHAR
(
    2
), input_params LONGTEXT, output_params LONGTEXT, excep LONGBLOB,
    gmt_end DATETIME
(
    3
), state_id_compensated_for VARCHAR
(
    128
), state_id_retried_for VARCHAR
(
    128
),
    gmt_updated DATETIME
(
    3
), PRIMARY KEY
(
    id,
    machine_inst_id
)
    );
