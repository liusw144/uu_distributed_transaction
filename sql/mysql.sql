-- 只在 dtx_demo_mysql 专用库内执行；所有表使用 InnoDB。
CREATE TABLE IF NOT EXISTS demo_orders (
  order_id VARCHAR(64) PRIMARY KEY COMMENT '请求业务号，也是幂等键',
  scenario VARCHAR(16) NOT NULL, user_id VARCHAR(40) NOT NULL,
  product_id VARCHAR(40) NOT NULL, quantity INT NOT NULL,
  amount DECIMAL(18,2) NOT NULL, status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS demo_stock (
  id VARCHAR(80) PRIMARY KEY COMMENT '方案:商品编号，不同方案独立库存',
  available INT NOT NULL,
  CONSTRAINT stock_nonnegative CHECK (available >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS demo_tasks (
  id VARCHAR(64) PRIMARY KEY, task_type VARCHAR(16) NOT NULL,
  payload TEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  lease_until TIMESTAMP(6) NULL, lease_token VARCHAR(36) NULL,
  last_error VARCHAR(1000) NULL, last_response VARCHAR(40) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_task_poll(task_type,status,next_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 与 Apache Seata 2.2.0 客户端 AT undo_log 结构对应。
CREATE TABLE IF NOT EXISTS undo_log (
  branch_id BIGINT NOT NULL, xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL, rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL, log_created DATETIME(6) NOT NULL,
  log_modified DATETIME(6) NOT NULL,
  UNIQUE KEY ux_undo_log(xid,branch_id), INDEX ix_log_created(log_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 重复初始化不重置余额和库存，便于观察历史事务。
INSERT IGNORE INTO demo_stock(id,available) VALUES
('XA:SKU-001',100),('OUTBOX:SKU-001',100),('AT:SKU-001',100);
