# 四种分布式一致性 Demo 设计

用户要求：Spring Boot + 本地 MySQL 8.0 + PostgreSQL，实现 XA、Outbox 定时补偿、Seata AT、最大努力通知，包含中文注释。

## 实现选择

采用 Maven 多模块工程，JDK 17、Spring Boot 3.3.13、Atomikos 6.0.0、Apache Seata 2.2.0。版本固定，先验证兼容性。MySQL 保存订单、库存、可靠任务；PostgreSQL 保存账户、消费回执和通知回执。四种方案各自使用隔离的演示数据。

比较过单体内手工模拟协议、每个方案复制完整微服务和共享基础模块三种组织方式。选择共享基础模块和独立应用，减少重复；XA 是一个应用协调两个真实 XA 资源；Seata 通过三个独立进程和 HTTP 传播 XID，保留真正的远程分支。

## 事务边界

- XA：Atomikos JTA 同时管理 MysqlXADataSource、PGXADataSource，订单、库存、账户原子提交；保留恢复日志，PG 必须启用 max_prepared_transactions。
- Outbox：MySQL 本地事务提交订单、预占库存、事件；HTTP 消费端在 PG 同一事务中记录幂等回执和扣款结果。余额不足记录 REJECTED，源端取消订单并释放库存。网络故障可重试，死信保留预占，人工重放时必须先查回执，不盲目退款。
- AT：订单服务开启 @GlobalTransactional；库存和账户服务绑定、清理 HTTP XID；所有业务连接使用 DataSourceProxy，每个数据库配置 undo_log，TC 使用本地 Seata Server。
- 通知：MySQL 本地提交已支付订单和通知任务，HTTP 通知 PG 回执端；有界退避、业务 ACK 校验、唯一键幂等、死信查询和人工重放。它不承担跨库原子支付。

## 可靠性与可验证性

任务采用短事务领取、租约和 fencing token，避免持有数据库事务等待网络；所有金额使用 BigDecimal，扣减使用带余额/库存条件的 UPDATE。重复业务号拒绝且不重复扣减。故障注入覆盖执行中回滚、接收端不可用、丢失 ACK。接口只监听 127.0.0.1，作为本地教学 Demo。

提供中文 README、SQL、配置示例、Windows 启动/停止/环境检查/HTTP 验证脚本。默认不修改数据库全局参数、不清空现有数据、不把密码提交到示例。实际连接信息通过本地配置或环境变量提供。
