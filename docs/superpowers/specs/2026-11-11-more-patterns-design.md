# TCC 与 Saga 增补设计

用户要求完善分布式事务方案，沿用本地 MySQL 8.0 / PostgreSQL、中文注释、每个 Demo 独立 README / requests.http / Java 测试，不依赖
ps1。

## 选择与边界

新增 tcc-demo（8092）和 saga-demo（8093）。保持已有 XA / Outbox / AT / 通知行为不变；RabbitMQ 的可靠投递与 Outbox
是可组合方案，事务消息、CDC、业务对账、2PC/3PC 放入选型说明，不把它们全部混为新的原子协议。

两个新 Demo 使用教学用的显式协调器，不声称是 Seata TCC/Saga 引擎。业务分支在同一进程中使用两个数据库的独立本地事务；协调器状态保存在
MySQL，分支状态与其资源变更在各自库内原子提交。

## 共用协调契约

common/cn/demo/common/workflow/ProtocolParticipant:

- String protocol()：TCC 或 SAGA。
- enum Resource { STOCK, ACCOUNT }
- void forward(Resource resource, Purchase p)：TCC Try 或 Saga 正向动作。
- void confirm(Resource resource, Purchase p)：TCC Confirm；Saga 不调用。
- void reverse(Resource resource, Purchase p)：TCC Cancel 或 Saga 补偿。
- Map<String,Object> snapshot()：stock、accounts、branches 等可观察状态。

WorkflowStore 保存 demo_workflows，并与 demo_orders 同一本地事务创建及完成。状态：
TCC PREPARING -> PREPARED（可手动确认，超时自动取消）/ CONFIRMING -> SUCCEEDED；失败走 CANCELLING -> CANCELLED。
Saga RUNNING -> SUCCEEDED；失败走 COMPENSATING -> COMPENSATED。
COMMIT 决定一旦落库不再转 Cancel。失败记录 attempts / next_retry_at / blocked，耗尽后须人工 retry，不能抹去决议。MySQL
会话命名锁避免常态重复驱动；状态 CAS 和分支幂等抵御迟到执行。

## 分支语义

TCC：专用库存/账户的 available、reserved、confirmed 三个数量，Try 原子扣 available 加 reserved，Confirm 转 reserved 为
confirmed，Cancel 释放 reserved。两库各自 tcc_branches 保存 payload 与状态，唯一键 + 行锁串行化。空 Cancel 建 CANCELLED
墓碑，迟到 Try 拒绝，Confirm 后不可 Cancel，重复请求内容冲突拒绝。

Saga：使用 SAGA 命名空间的 demo_stock / demo_accounts，先真实扣库存、再扣余额；补偿按账户退款、库存恢复反向执行。两库的
saga_*_actions 与资源变更同一本地事务提交。补偿可先到，墓碑阻止迟到正向动作；重复执行与补偿不重复扣/加。

## 故障与恢复

保留 Purchase.failAt 的 AFTER_ORDER/STOCK/ACCOUNT。独立 WorkflowFaults 可注入 confirm/cancel/compensate 的调用前失败和提交后
ACK 丢失。自动恢复每秒驱动非终态；退避有上限、失败耗尽显式 blocked，支持人工 retry。TCC prepareOnly 用于观察冻结，默认 60
秒超时取消。
不假设跨库隔离，Saga 补偿是业务语义恢复，不是 SQL 回滚；生产还需独立服务通信、认证、告警及更完整协调器。

## 验证

真实两库测试覆盖成功、业务不足、重复与并发、空回滚、迟到请求、冲突载荷、Confirm 不反悔、补偿失败重试及恢复。每模块 SQL
幂等追加，不删数据或修改数据库全局参数。HTTP 场景和 SQL 查询放各模块目录。
