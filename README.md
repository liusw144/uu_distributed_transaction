# 分布式事务一致性：四种方案完整 Demo

Spring Boot + 本地 MySQL 8.0 + PostgreSQL，通过订单、账户、库存和通知观察四种一致性机制。关键事务边界、故障处理均有中文注释。

| 方案 | 实现 | 启动端口 | 观察重点 |
|---|---|---|---|
| XA 两阶段提交 | Atomikos JTA + 两个真实 XA 数据源 | 8081 | 两库原子提交、失败回滚、持久化恢复日志 |
| 消息表 + 定时补偿 | MySQL Outbox + HTTP 投递 + PG 幂等消费 | 8082 | 本地提交、重复消息、余额不足补偿、死信 |
| Seata AT | 三个独立 HTTP 服务 + DataSourceProxy + undo_log + TC | 8083 / 8084 / 8085 | XID 传播、分支本地提交、全局回滚 |
| 最大努力通知 | 支付事实 + 持久化通知任务 + HTTP 回调 | 8086 | 有界重试、业务 ACK、幂等回执、人工重放 |

技术版本固定为 JDK 17、Maven 3.9、Spring Boot 3.3.13、Atomikos 6.0.0、Apache Seata 2.2.0。它们用于本地学习复现，版本升级应重新进行兼容性验证。数据库使用本机 MySQL 8.0（**3308**）及 PostgreSQL（5432），不需要 Docker、Nacos、Redis 或 MQ。

## 1. 快速开始（Windows PowerShell）

在项目根目录打开终端：

```powershell
cd D:\self\uu_distributed_transaction
# 本次交付已按提供的连接信息填写 local.ps1；重新搭建时才需要复制。
Copy-Item config/local.example.ps1 config/local.ps1
# 编辑 config/local.ps1，填写两个数据库密码、JDK 与客户端路径。
./scripts/init-db.ps1
./scripts/build.ps1 -DatabaseTests
./scripts/start.ps1 outbox
./scripts/start.ps1 notify
./scripts/smoke.ps1
```

`init-db.ps1` 只创建专用库 `dtx_demo_mysql`、`dtx_demo_pg` 及演示表，不清空已有记录。种子数据按方案隔离，首次库存各 100、账户余额各 10000.00。重复初始化不会重置余额和库存。

`config/local.ps1` 已加入 `.gitignore`；不需要把密码写进 Java 或 YAML。也可以直接设置 `MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`、`PG_URL`、`PG_USER`、`PG_PASSWORD` 后启动 jar。脚本从 host/port 生成 URL；直接启动 jar 时使用完整 URL。Maven 默认使用项目 `.runtime/m2` 缓存及 HTTPS Central，不修改系统 Maven 设置。

执行数据库集成测试时请先停止本项目应用，避免定时任务抢占测试事件：

```powershell
./scripts/stop.ps1
./scripts/build.ps1 -DatabaseTests
```

仅编译及离线单元测试可运行 `./scripts/build.ps1`，数据库集成用例会明确标记 skipped。普通 `mvn verify` 也可使用，但会遵循你本机 Maven/JDK 配置。

## 2. XA：真实两阶段提交

核心文件：`xa-demo/.../XaDataSourceConfig.java` 和 `XaService.java`。

MySQL `MysqlXADataSource` 与 PG `PGXADataSource` 分别由 Atomikos 池管理，`@Transactional` 使用 JTA 全局事务。方法内写 MySQL 订单、扣库存、扣 PG 余额，所有参与者 prepare 成功后才作出 commit 决议。任一步异常均回滚。

PostgreSQL 默认 `max_prepared_transactions=0`，必须先启用：

```powershell
# 修改实例参数，需要数据库超级用户；不重启不会生效。
./scripts/enable-pg-xa.ps1
# 在管理员 PowerShell 中执行，期间 PG 的其他连接会短暂中断。
Restart-Service postgresql-x64-16
./scripts/check-env.ps1
./scripts/start.ps1 xa
```

启用 XA 参数后，可执行 `./scripts/build.ps1 -DatabaseTests -XaTests`，额外验证真实 XA 两库提交及扣款后回滚。运行前先停止本项目应用。

也可以在管理员终端直接运行 `./scripts/enable-pg-xa.ps1 -RestartService`。应用在启动期检查此参数，未开启会明确失败。

事务日志保存在 `.runtime/xa-logs`。**发生进程崩溃后，恢复时使用同一日志目录、事务管理器名称和 XA 资源名称**；不要为了绕过启动错误删除日志，也不要手工提交/回滚未知来源的 prepared transaction。教学配置仅允许启动一个 XA 实例；多实例须分别配置管理器名称与日志目录。

```sql
-- 在 PG 查看悬而未决的 prepared transaction。
SELECT gid, prepared, owner, database FROM pg_prepared_xacts;
```

## 3. Outbox：本地消息表 + 定时补偿

流程：

1. MySQL `mysqlTx` 同时插入 PENDING 订单、预占库存、插入 `demo_tasks`。
2. 定时任务短事务领取消息，设置 30 秒租约与唯一令牌，然后在事务外调用 PG 消费端 HTTP 接口。
3. 消费端在一个 `pgTx` 中写 `demo_receipts` 和扣款。`event_id` 唯一约束处理并发重复，重复事件返回原结果；同编号不同内容拒绝。
4. 源端收到 PAID 后确认订单；收到 REJECTED（余额不足）后取消订单并释放库存。订单状态 CAS、库存释放和任务 DONE 在同一 MySQL 事务内提交。

消费者已扣款但 HTTP 响应丢失时，发送端会再次发送相同事件，PG 回执阻止重复扣款。网络超时只代表结果未知，不能直接退款或释放库存。

默认使用本进程的 HTTP 消费端，以减少启动项；源端和消费端的本地事务完全独立。可通过 `OUTBOX_CONSUMER_URL` 指向另一实例的 `/api/outbox/consume`，验证独立部署与接收端宕机。事件通过 HTTP 投递，没有隐含依赖外部消息队列。

## 4. Seata AT：三个独立服务

先准备官方 Seata Server 2.2.0：

```powershell
./scripts/install-seata.ps1
./scripts/start-seata.ps1
./scripts/start.ps1 at
```

如果已安装官方二进制包，在 `config/local.ps1` 配置 `$env:SEATA_HOME` 即可跳过下载。启动脚本使用本项目 `config/seata-server.yml`；TC 通信端口 8091，控制台 7091。file 模式事务存储位于 `.runtime/seata-data`，崩溃恢复时必须保留。控制台仅为本地演示，用户/密码为 seata/seata。

三个独立 Java 进程分别为订单（8083）、库存（8084）、账户（8085）。订单服务 `@GlobalTransactional` 开启全局事务，HTTP 请求头 `TX_XID` 传播 XID；接收端过滤器在 finally 中清理线程上下文。分支缺少 XID 时直接拒绝执行。

所有分支业务 JDBC 都使用显式 `DataSourceProxy`，每个数据库都有匹配方言的 `undo_log`。自动数据源代理已禁用，避免二次代理。第一阶段各分支本地提交业务记录与 Undo Log；全局失败时 TC 驱动二阶段回滚。成功提交后 Undo Log 异步清理，因此不应把“表内瞬时还有 Undo Log”误判为失败。

普通查询可能观察到 AT 尚未全局完成的分支数据，AT 的隔离性依赖参与写入者遵守 Seata 全局锁协议，不能把它理解为数据库原生 XA 的隔离语义。演示期间不要通过管理工具修改参与回滚的业务行，否则可能触发脏数据校验并需要人工处理。

## 5. 最大努力通知

`POST /api/notify/payments` **记录已由支付平台确认的支付事实**，同一 MySQL 本地事务写 PAID 订单及通知任务。它没有跨库原子扣款职责，因此本方案不扣账户或库存。

后台 HTTP 调用 `/api/notify/callback`，对方在 PG 持久化回执后返回 `{"result":"ACK"}`。只有有效业务 ACK 才能标记 DONE，HTTP 200 空内容或未知 ACK 仍进入重试。重复回调返回相同 ACK。

默认最多领取 8 次，间隔 1、2、4、8…秒，上限 60 秒。可在启动前配置 `RETRY_MAX_ATTEMPTS`、`RETRY_BASE_SECONDS`、`RETRY_MAX_SECONDS`。超过上限进入 DEAD；最后一次领取后进程崩溃，租约过期也会转 DEAD。重放仍使用原事件编号和 payload，确保幂等。

```powershell
# 让通知接收端连续失败，便于观察重试与死信。
Invoke-RestMethod 'http://127.0.0.1:8086/demo/faults?failNext=100' -Method Post
Invoke-RestMethod 'http://127.0.0.1:8086/api/notify/tasks'
# 故障恢复后先查回执，再重放 DEAD 任务。
Invoke-RestMethod 'http://127.0.0.1:8086/demo/faults' -Method Post
Invoke-RestMethod 'http://127.0.0.1:8086/api/notify/receipts/你的业务号'
Invoke-RestMethod 'http://127.0.0.1:8086/api/notify/tasks/你的业务号/replay' -Method Post
```

Outbox 使用同样的死信重放接口：`/api/outbox/tasks/{id}/replay`。Outbox DEAD 时订单保持 PENDING、库存保持预占，远端可能已经扣款，应先查询回执并重放确认。若业务要求主动取消，需要额外设计幂等退款流程，不能直接释放库存后再让旧消息继续扣款。

## 6. 接口与故障注入

统一订单请求：

```json
{
  "orderId": "order-20261107-001",
  "userId": "U1001",
  "productId": "SKU-001",
  "quantity": 2,
  "unitPrice": 10.50,
  "failAt": "NONE"
}
```

业务号必须跨四个方案全局唯一，重试应先查询结果。金额使用 BigDecimal，禁止负数、零数量和超过两位小数的金额。账户和库存通过带条件的 UPDATE 扣减，不会产生负数。

| 方法 | 地址 | 用途 |
|---|---|---|
| POST | `8081/api/xa/orders` | XA 下单 |
| GET | `8081/api/xa/state` | XA 两库数据 |
| POST | `8082/api/outbox/orders` | 可靠消息下单 |
| GET | `8082/api/outbox/state` | 订单、库存、余额 |
| GET | `8082/api/outbox/tasks` | 投递状态、次数及错误 |
| GET | `8082/api/outbox/receipts/{id}` | PG 扣款回执 |
| POST | `8082/api/outbox/tasks/{id}/replay` | 死信重放 |
| POST | `8083/api/at/orders` | AT 下单 |
| GET | `8083/api/at/state` | AT 两库数据 |
| POST | `8086/api/notify/payments` | 记录支付事实并通知 |
| GET | `8086/api/notify/tasks` | 通知任务 |
| GET | `8086/api/notify/receipts/{id}` | 通知回执 |
| POST | `8086/api/notify/tasks/{id}/replay` | 通知死信重放 |

上述地址均加 `http://127.0.0.1:` 前缀。

`failAt` 支持 NONE、AFTER_ORDER、AFTER_STOCK、AFTER_ACCOUNT。XA / AT 在对应业务写入后抛异常，用于验证全局回滚。Outbox 的 AFTER_ACCOUNT 表示写入消息后的本地提交前故障，实际扣款由异步消费端执行。通知使用 AFTER_ORDER 验证支付事实和任务的原子落库。

每个应用都提供 `/demo/health`，Outbox/通知还使用公共故障控制接口：

```powershell
# 消费端先失败两次，随后成功提交但丢一次 ACK。
Invoke-RestMethod 'http://127.0.0.1:8082/demo/faults?failNext=2&loseAckNext=1' -Method Post
```

失败注入计数是本地内存状态，重启会清零；业务任务、重试次数和幂等回执会持久化。为便于本地演示，这些接口没有身份认证，应用只监听 127.0.0.1。

## 7. 验证与停止

```powershell
./scripts/build.ps1 -DatabaseTests
# 已启动 Outbox / notify 后验证 HTTP 故障场景。
./scripts/smoke.ps1
# XA、TC 和三个 AT 服务也启动后：
./scripts/smoke.ps1 -IncludeXa -IncludeAt
# 可选：验证通知失败耗尽、ACK 丢失耗尽、查询回执和幂等重放。
# 默认 8 次重试时每种场景约两分钟；演示时可在启动前设 RETRY_MAX_ATTEMPTS=3。
./scripts/dead-letter.ps1
./scripts/stop.ps1
# 也可只停止一个角色：
./scripts/stop.ps1 -Name 'at-*'
```

数据库测试覆盖本地事务回滚、条件扣款、重复消费、内容冲突、余额不足库存补偿、过期租约旧工作者隔离、最后一次领取崩溃死信和通知幂等。HTTP 脚本真正运行故障、重试和两库结果断言；会保留演示订单供检查。种子库存和余额会消耗，不要无限反复运行。

启动记录：`.runtime/pids`；应用日志：`.runtime/logs`。停止脚本校验 PID 与进程启动时间，防止 PID 复用误停其他进程。XA / TC 的恢复日志在停止后保留。

## 8. 项目结构与资料

```text
common/              请求校验、数据库访问、可靠任务与幂等回执
xa-demo/             Atomikos + XA 两阶段提交
outbox-demo/         Outbox 本地事务与异步消费
seata-demo/          AT 订单、库存、账户三个独立角色
notification-demo/  支付事实与最大努力通知
sql/                 两库建表、种子数据、undo_log
config/              本地连接配置示例和 Seata TC 配置
scripts/             建库、构建、启动、停止及 HTTP 验证
docs/                设计、执行计划和验证记录
```

实现依据：[Seata AT 数据源代理及阶段机制](https://seata.apache.org/docs/user/mode/at/)、[Atomikos Spring Boot 集成](https://www.atomikos.com/Documentation/SpringBootIntegration)、[PostgreSQL prepared transaction 参数](https://www.postgresql.org/docs/16/runtime-config-resource.html#GUC-MAX-PREPARED-TRANSACTIONS)、[Seata 官方 2.2.0 归档](https://archive.apache.org/dist/incubator/seata/2.2.0/)。
