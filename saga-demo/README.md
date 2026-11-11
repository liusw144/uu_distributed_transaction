# Saga：已提交动作的业务补偿

本模块是手写的教学 Saga，运行在同一进程中，跨 MySQL 库存/订单和 PostgreSQL 账户两个数据库。无需额外 TC，不使用 Seata Saga
状态机引擎。

验证入口：[requests.http](requests.http)
；真实数据库测试：[SagaParticipantTest.java](src/test/java/cn/demo/saga/SagaParticipantTest.java)。

## 启动

1. 完成[根目录说明](../README.md)中的 JDK
   17、Maven、两库准备。连接读取 [application.yml](src/main/resources/application.yml) 的 `demo.datasource`：MySQL
   `3308 root/root`、PG `5432 postgres/postgres`，修改连接直接编辑 YAML，无需数据库环境变量。
2.
先执行根目录的 [MySQL SQL](../sql/mysql.sql)、[MySQL 协调表 SQL](../sql/workflow-mysql.sql)、[PostgreSQL SQL](../sql/postgresql.sql)
，再在各自专用库执行本模块 [MySQL 增量 SQL](sql/mysql.sql)、[PostgreSQL 增量 SQL](sql/postgresql.sql)。SQL 幂等建表和初始化
`SAGA:SKU-001`（库存 100）、`SAGA:U1001`（余额 10000.00），重复执行保留已有数值。
3. 从工程根目录构建并运行，或在 IDEA 中运行 `cn.demo.saga.SagaApplication`，工作目录为工程根目录。

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl saga-demo -am package
java -jar saga-demo/target/saga-demo-1.0.0.jar
```

默认监听 `127.0.0.1:8093`，可用 `SERVER_PORT` 修改端口。首次查询 `GET http://127.0.0.1:8093/api/saga/state` 记录库存 S、余额
B。启动、建表、测试都不需要运行 ps1。

## 事务与恢复

公共持久化协调器先在 MySQL 创建 `PENDING` 订单及 `RUNNING` 工作流，再依次提交库存扣减、账户扣款。全部成功后订单为 `PAID`
，工作流为 `SUCCEEDED`。

正向动作失败时，协调器保存 `COMPENSATING` 决议，按账户退款、库存归还的顺序执行补偿。补偿全部完成后订单为 `CANCELLED`、工作流为
`COMPENSATED`。业务注入失败仍返回 HTTP 200 和工作流 JSON；检查 `status` 判断结果。相同订单号、相同业务内容会复用已有工作流；业务内容冲突返回
HTTP 409。

两个分支各有动作表 `saga_stock_actions` / `saga_account_actions`，状态为 `NEW -> APPLIED -> COMPENSATED`
。动作流水和资源增减在资源库的同一本地事务内提交。唯一键和 `SELECT ... FOR UPDATE` 串行化同一分支；条件 UPDATE
保证库存/余额不出现负数，金额使用 `BigDecimal`。重复动作只读已有结果，不重复增减。空补偿提交 `COMPENSATED`
墓碑，拒绝迟到的正向动作。相同编号换用户、商品、数量或单价会被拒绝；故障注入标记不属于业务内容。

补偿失败或提交后 ACK 丢失时，工作流保留 `COMPENSATING`，订单保留 `PENDING`。每秒的恢复任务按退避策略重试；重试耗尽时
`blocked=true`，保留待补偿决议。排除故障后调用原订单的 `POST /api/saga/orders/{id}/retry` 恢复补偿，不能改换业务号来替代恢复。分支幂等防止
ACK 丢失后重复退款。协调器、REST 和自动恢复由 common 提供，本模块只提供一个 `ProtocolParticipant` Bean。

| 配置                               | 默认值    | 作用                          |
|----------------------------------|--------|-----------------------------|
| `demo.workflow.max-attempts`     | `8`    | 失败重试上限，耗尽后设置 `blocked=true` |
| `demo.workflow.interval-ms`      | `1000` | 自动恢复扫描间隔，单位毫秒               |
| `demo.workflow.recovery-enabled` | `true` | 是否启用自动恢复任务                  |

重试从 1 秒开始指数退避，上限 60 秒；恢复任务只扫描已到重试时间的工作流。快速观察 blocked 时，将启动命令改为：

```text
java -jar saga-demo/target/saga-demo-1.0.0.jar --demo.workflow.max-attempts=2
```

IDEA 中同样可把 `--demo.workflow.max-attempts=2` 填入 Program arguments。设置 `recovery-enabled=false` 可关闭后台恢复，便于按步骤观察和手动
retry。

Saga 的正向动作已经提交，中间态对其他事务可见。补偿是另一次业务事务，不是数据库原生回滚，也不提供跨库隔离。库存补回和退款本身可能失败，需要持久化恢复与人工介入。生产系统还需独立服务通信、认证、告警和完整的业务冲突处理。

## HTTP 验证

按 [requests.http](requests.http) 分组逐条执行；每轮修改 `@run`，等待当前订单达到终态后再开始下一组。

| 场景              | 预期工作流与资源变化                                       |
|-----------------|--------------------------------------------------|
| 正常下单并重复同一请求     | `SUCCEEDED` / `PAID`；库存 S-1、余额 B-10，仅扣一次         |
| `AFTER_ORDER`   | `COMPENSATED` / `CANCELLED`；两个空补偿墓碑，资源不变         |
| `AFTER_STOCK`   | 库存补回，账户空补偿；资源回到本组基线                              |
| `AFTER_ACCOUNT` | 先退款后补回库存；资源回到本组基线                                |
| 余额不足            | 账户扣减及其 NEW 流水回滚，随后补偿库存；资源回到本组基线                  |
| 补偿 ACK 丢失一次     | 可能短暂 `COMPENSATING`，恢复后 `COMPENSATED`；账户只退款一次    |
| 补偿持续失败后重试耗尽     | `COMPENSATING` 且 `blocked=true`；清故障并手动 retry 后恢复 |

故障端点：`POST /api/saga/faults?phase=compensate&failNext=N&loseAckNext=M`。`failNext` 在补偿调用前失败；`loseAckNext`
在分支提交后模拟丢失应答。该配置为进程内共享计数，不要在同时运行的订单之间混用。

返回的工作流包含 `id`、`protocol`、`purchase`、`status`、`step`、`attempts`、`blocked` 等字段。`GET /api/saga/orders/{id}`
查询单笔工作流；`GET /api/saga/state` 返回 `stock`、`accounts`、`branches`，以及公共协调器补充的 `orders`、`workflows`。Saga
不使用 Confirm 阶段。

## SQL 核对

MySQL `dtx_demo_mysql`：

```sql
SELECT id,available FROM demo_stock WHERE id='SAGA:SKU-001';
SELECT order_id,status,quantity,amount FROM demo_orders
WHERE scenario='SAGA' ORDER BY created_at DESC LIMIT 20;
SELECT tx_id,state,payload FROM saga_stock_actions ORDER BY tx_id;
SELECT * FROM demo_workflows WHERE protocol='SAGA';
```

PostgreSQL `dtx_demo_pg`：

```sql
SELECT id,balance FROM demo_accounts WHERE id='SAGA:U1001';
SELECT tx_id,state,payload FROM saga_account_actions ORDER BY tx_id;
```

成功订单的两个动作都是 `APPLIED`；补偿完成的两个动作都是 `COMPENSATED`。失败补偿仍保留可恢复状态。只看动作存在不能判断是否扣款，必须连同
`state` 和实际资源核对。

## 真实数据库测试

设置 `DEMO_DB_TESTS=true`，并提供 `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` / `PG_URL` / `PG_USER` / `PG_PASSWORD`，使用
JDK 17 从工程根目录运行：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl saga-demo -am test
```

未启用 `DEMO_DB_TESTS` 时跳过真库测试。用例直接构造参与者，使用 UUID
隔离的库存、账户和动作流水，执行后清理；不扣减默认演示资源。覆盖两库的重复/并发正向与补偿、空补偿防悬挂、完成补偿后拒绝迟到动作、业务内容冲突、金额尺度一致性、资源不足回滚流水、并发不同订单不超扣，以及补偿资源丢失后保留
APPLIED 以便重试。公共协调器的状态推进、重试和恢复另由 common 的测试验证。
