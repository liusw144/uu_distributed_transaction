# 四种分布式事务实现计划

> 执行方式：本会话直接实现；依照 test-driven-development 和 verification-before-completion 验证。

**Goal:** 在指定空目录交付可运行、可观察、中文注释的四种一致性方案。

**Architecture:** common 共享请求、数据库访问、可靠任务；xa-demo、outbox-demo、seata-demo、notification-demo 各自启动。Seata 同一 jar 按角色启动三个进程。

**Tech Stack:** JDK 17 / Maven / Spring Boot 3.3.13 / MySQL 8.0 / PostgreSQL 16 / Atomikos 6.0.0 / Seata 2.2.0。

**Spec:** docs/superpowers/specs/2026-11-07-distributed-transactions-design.md

## Global Constraints

- 中文文档和关键逻辑注释；密码仅本地配置；金额 BigDecimal；只操作专用 demo 库。
- 不自动调整 PostgreSQL 全局参数或重启现有数据库。

## Review Focus

- 重复消息及 ACK 丢失必须只扣一次款。
- 本地事务失败不能留下孤立订单、库存预占或消息。
- 余额不足取消订单时只能释放一次库存。
- 最后一次领取后崩溃不能永久停留在 PROCESSING。
- Seata HTTP 异常必须清理线程 XID，所有业务写入均经过代理。

## Tasks

- [x] 1. 创建 Maven 模块和基础配置；先写金额/输入验证测试，观察失败后实现。
- [x] 2. 写业务与任务数据库集成测试，覆盖回滚、幂等、库存和重试；实现 common 和 Outbox。
- [x] 3. 实现真实 XA 与 AT，提供成功及故障注入验证接口。
- [x] 4. 实现通知发送与接收、失败重试、死信重放，复用可靠任务。
- [x] 5. 提供两库 SQL、配置及启动/验证脚本，运行 Maven 全量构建、可用环境的数据库与 HTTP 验证。

## 执行记录

- 初始目录为空且无 Git 仓库，直接在用户指定目录工作，不创建 worktree 或提交。
- 本机 MySQL80 配置端口为 3308；不能使用 3306 默认值误连另一版本。
- 用户授权将 PG max_prepared_transactions 从 0 改为 100 并重启；通过 Windows 管理员权限完成，已验证生效。
- 独立审查确认 PG undo_log 缺少序列字段。添加实际 Seata 适配器回归测试，先复现缺列错误，再补 BIGSERIAL 与幂等迁移，测试通过。
- AT 启动验证暴露默认事务管理器歧义，明确 mysqlTx 为 Primary，账户分支仍显式使用 pgTx；三进程启动及全局回滚验证通过。
- 最终全量 Maven verify：15 测试，0 失败/错误/跳过；四方案 HTTP smoke 与两类通知死信重放通过。详细证据见 docs/verification.md。
