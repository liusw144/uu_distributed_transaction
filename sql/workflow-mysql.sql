-- 在 dtx_demo_mysql 执行。协调日志与订单同库；不能清空尚未结束的事务记录。
CREATE TABLE IF NOT EXISTS demo_workflows
(
    id
    VARCHAR
(
    64
) PRIMARY KEY,
    protocol VARCHAR
(
    12
) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR
(
    24
) NOT NULL,
    step INT NOT NULL DEFAULT 0,
    manual_commit BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INT NOT NULL DEFAULT 0,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    next_retry_at DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
),
    expires_at DATETIME
(
    6
) NOT NULL,
    last_error VARCHAR
(
    1000
),
    created_at DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
),
    updated_at DATETIME
(
    6
) NOT NULL DEFAULT CURRENT_TIMESTAMP
(
    6
),
    INDEX idx_workflow_recovery
(
    protocol,
    blocked,
    status,
    next_retry_at
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
