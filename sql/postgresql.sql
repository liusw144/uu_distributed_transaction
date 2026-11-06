-- 只在 dtx_demo_pg 专用库内执行。
CREATE TABLE IF NOT EXISTS demo_accounts (
  id VARCHAR(80) PRIMARY KEY,
  balance NUMERIC(18,2) NOT NULL CHECK(balance >= 0)
);
CREATE TABLE IF NOT EXISTS demo_receipts (
  event_id VARCHAR(64) PRIMARY KEY,
  receipt_type VARCHAR(16) NOT NULL,
  payload TEXT NOT NULL,
  result VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- PG AT 使用 BYTEA，不可照搬 MySQL 的 LONGBLOB。
CREATE TABLE IF NOT EXISTS undo_log (
  id BIGSERIAL PRIMARY KEY,
  branch_id BIGINT NOT NULL, xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL, rollback_info BYTEA NOT NULL,
  log_status INT NOT NULL, log_created TIMESTAMP(6) NOT NULL,
  log_modified TIMESTAMP(6) NOT NULL,
  CONSTRAINT ux_undo_log UNIQUE(xid,branch_id)
);
-- 兼容首次演示初始化时尚未包含 id 的表；Seata 2.2 PG 适配器会调用 undo_log_id_seq。
ALTER TABLE undo_log ADD COLUMN IF NOT EXISTS id BIGSERIAL;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='undo_log'::regclass AND contype='p') THEN
    ALTER TABLE undo_log ADD CONSTRAINT pk_undo_log PRIMARY KEY(id);
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_undo_log_created ON undo_log(log_created);
INSERT INTO demo_accounts(id,balance) VALUES
('XA:U1001',10000.00),('OUTBOX:U1001',10000.00),('AT:U1001',10000.00)
ON CONFLICT(id) DO NOTHING;
