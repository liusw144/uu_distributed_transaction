# TCC：先冻结，再确认或释放

本示例演示显式 Try / Confirm / Cancel 协议。库存位于 MySQL，账户位于 PostgreSQL，协调器日志及订单位于 MySQL。每个分支的资源变更与
`tcc_branches` 日志在所属数据库的同一本地事务中提交。默认监听 `127.0.0.1:8092`。

这是单进程教学协调器；生产中的独立服务通信、认证、告警和分布式协调器需要另行实现。`GET /state` 依次读取两个数据库，用于观察状态，不提供跨库一致快照。

## 初始化与启动

要求 JDK 17、Maven、MySQL 8.0、PostgreSQL。先创建专用数据库 `dtx_demo_mysql` / `dtx_demo_pg`，在工程根目录用数据库客户端执行根
SQL，再执行模块 SQL。所有建表及种子语句均可重复运行，不重置已有余额和库存。

```bash
mysql -h 127.0.0.1 -P 3308 -u root -p dtx_demo_mysql < sql/mysql.sql
mysql -h 127.0.0.1 -P 3308 -u root -p dtx_demo_mysql < sql/workflow-mysql.sql
psql -h 127.0.0.1 -p 5432 -U postgres -W -d dtx_demo_pg -f sql/postgresql.sql
mysql -h 127.0.0.1 -P 3308 -u root -p dtx_demo_mysql < tcc-demo/sql/mysql.sql
psql -h 127.0.0.1 -p 5432 -U postgres -W -d dtx_demo_pg -f tcc-demo/sql/postgresql.sql
```

命令中的输入重定向适用于 Bash / CMD；也可在数据库客户端打开相应 SQL 文件执行。IDEA 选择 JDK 17，直接运行
`cn.demo.tcc.TccApplication`，工作目录为项目根目录。连接读取 [application.yml](src/main/resources/application.yml) 的
`demo.datasource`：MySQL `3308 root/root`、PG `5432 postgres/postgres`，修改连接直接编辑 YAML；无需数据库环境变量或 `.ps1`。

```bash
mvn -B -s scripts/maven-settings.xml -Dmaven.repo.local=.runtime/m2 -pl tcc-demo -am package -DskipTests
java -jar tcc-demo/target/tcc-demo-1.0.0.jar
```

## 观察冻结与完成

使用 [requests.http](requests.http)，每次新实验更换业务号，重试保留原业务号及业务内容。初始 `SKU-001` 库存 100，`U1001` 余额
10000.00；示例购买 2 件、单价 5.00。

| 动作                                      | 库存 available / reserved / confirmed | 账户 available / reserved / confirmed | workflow / 订单         |
|-----------------------------------------|-------------------------------------|-------------------------------------|-----------------------|
| 初始                                      | 100 / 0 / 0                         | 10000 / 0 / 0                       | 无                     |
| `POST /api/tcc/orders?prepareOnly=true` | 98 / 2 / 0                          | 9990 / 10 / 0                       | PREPARED / PENDING    |
| `POST /api/tcc/orders/{id}/confirm`     | 98 / 0 / 2                          | 9990 / 0 / 10                       | SUCCEEDED / PAID      |
| 从 PREPARED 执行 `/cancel`                 | 100 / 0 / 0                         | 10000 / 0 / 0                       | CANCELLED / CANCELLED |

表中确认与取消是同一初始状态的两个独立实验；重复执行实验不会重置资源。省略 `prepareOnly` 或传 `false` 时自动进行
Confirm。PREPARED 默认 60 秒到期后自动 Cancel。

`GET /api/tcc/orders/{id}` 返回持久化 workflow；`GET /api/tcc/state` 包含 `stock`、`accounts`、`branches.stock`、
`branches.account`、`orders` 与 `workflows`。创建接口返回 Flow JSON，包括 `id`、`protocol`、`purchase`、`status`、`step`、
`attempts`、`blocked`；业务注入失败仍返回 HTTP 200，通过 `status` 判断取消完成或等待恢复。

## 故障与恢复

请求体中的 `failAt=AFTER_ORDER|AFTER_STOCK|AFTER_ACCOUNT` 在对应步骤后模拟业务失败；`NONE` 为正常路径。已冻结的资源按账户、库存顺序
Cancel。

`POST /api/tcc/faults?phase=confirm&failNext=1&loseAckNext=1` 设置二阶段故障：`failNext` 在调用参与者前失败，`loseAckNext`
在分支提交后丢失响应。`phase=cancel` 同样支持。设置为零可清除剩余故障。故障计数按分支调用消耗，适用于下一次匹配阶段调用。

自动恢复每秒检查非终态工作流，并按退避重试；耗尽后保留原决议且设置 `blocked=true`。故障解除后
`POST /api/tcc/orders/{id}/retry` 继续原阶段。COMMIT 决议一旦持久化，后续故障只能重试 Confirm，不能改为 Cancel；矛盾命令返回
HTTP 409。

分支状态 `NEW -> RESERVED -> CONFIRMED` 或 `CANCELLED`。唯一键及行锁串行化重复动作，重复调用不重复扣款、确认或释放；同一业务号的不同业务内容被拒绝，
`failAt` 不参与业务内容比较。空 Cancel 写 `CANCELLED` 墓碑以拒绝迟到 Try。资源不足时本地事务整体回滚，不留下 `NEW` 分支。

## 测试与 SQL 检查

在已经配置数据库连接的终端设置 `DEMO_DB_TESTS=true` 后运行：

```bash
mvn -B -s scripts/maven-settings.xml -Dmaven.repo.local=.runtime/m2 -pl tcc-demo -am test
```

未启用 `DEMO_DB_TESTS` 时真实数据库测试会跳过。测试自动应用模块建表
SQL，使用随机商品、账户和业务号，只删除自己的测试行。测试覆盖冻结、确认/取消幂等、空取消、迟到请求、已确认分支拒绝取消、载荷冲突、并发以及不足时本地回滚。

MySQL 中查询：

```sql
SELECT * FROM tcc_stock;
SELECT tx_id,state,payload FROM tcc_branches ORDER BY tx_id;
SELECT * FROM demo_orders WHERE scenario='TCC';
SELECT * FROM demo_workflows WHERE protocol='TCC';
```

PostgreSQL 中查询：

```sql
SELECT * FROM tcc_accounts;
SELECT tx_id,state,payload FROM tcc_branches ORDER BY tx_id;
```

对每个资源观察 `available + reserved + confirmed` 守恒且各项非负；`confirmed` 表示已经完成的累计消耗。

公共恢复配置：demo.workflow.max-attempts 默认 8，interval-ms 默认 1000，prepare-timeout-seconds 默认 60，recovery-enabled 默认
true。退避从 1 秒指数增长，最大 60 秒。手动预留需要在 expiresAt 前确认；二阶段确认决议一旦写入，即使超过预留截止时间也必须继续
Confirm。
