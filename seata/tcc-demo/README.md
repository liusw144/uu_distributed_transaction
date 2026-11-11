# Apache Seata TCC（8103）

本模块 Java 17 / Spring Boot 3.3.13 / Seata 2.0.0，真实 TM/RM 连接本机 TC 127.0.0.1:8091。一个 JVM 内有两个独立数据库：MySQL
订单和库存合并为一个 TCC 动作，PostgreSQL 余额为另一个动作。单 JVM 降低演示启动成本，仍由 TC 注册、驱动和重试二阶段；本例不演示跨
JVM RPC 传播。

`TccService.purchase` 使用 `@GlobalTransactional`，接口使用 `@LocalTCC`、`@TwoPhaseBusinessAction`。业务不主动调用
Confirm/Cancel。每次 Try 先注册 TC 分支，然后同库事务持久化预留资源及 XID/branch_id。TC 决定 Confirm 消费冻结或 Cancel
释放冻结。

## 准备数据库和 TC

工作目录使用仓库根目录（以下命令在 PowerShell 直接执行，不调用 ps1）。安装 JDK 17、Maven 3.9、MySQL 和 PostgreSQL；数据库地址默认
MySQL 127.0.0.1:3308、PostgreSQL 127.0.0.1:5432。

首次创建数据库，可在 IDEA Database Console 中分别执行：

```sql
-- MySQL 管理连接
CREATE DATABASE IF NOT EXISTS dtx_demo_mysql;
-- PostgreSQL 管理连接，仅首次执行；CREATE DATABASE 不允许置于事务中
CREATE DATABASE dtx_demo_pg;
```

TC 必须使用 Seata 2.0.0，以 file 注册发现启动，监听 8091；不用 Nacos/Redis/RabbitMQ。若已有本仓库 TC 运行，请直接复用。本机现有
TC 的直接 Java 启动命令见 [Seata 总说明](../README.md)，不依赖 bin/bat/ps1 脚本；先按总说明启动 TC，再启动本模块。

TC 使用 DB 模式，需保留 MySQL 的 seata_tc_200 协调库；客户端重启仍使用同一个 application-id 和 TC。不要在有待处理分支时清理
TC 协调表或业务分支表。

连接读取 [application.yml](src/main/resources/application.yml) 的 `demo.datasource`：MySQL `3308 root/root`、PG
`5432 postgres/postgres`，修改连接直接编辑 YAML。无需配置数据库环境变量，原 application.properties 已迁移至 YAML。

```powershell
$env:JAVA_HOME='D:/software/java/jdk17/jdk17.0.15_6'
$env:PATH="$env:JAVA_HOME/bin;D:/software/apache-maven-3.9.12/bin;$env:PATH"
mvn -B -s scripts/maven-settings.xml '-Dmaven.repo.local=.runtime/m2' -pl seata/tcc-demo -am install -DskipTests
mvn -B -s scripts/maven-settings.xml '-Dmaven.repo.local=.runtime/m2' -f seata/tcc-demo/pom.xml spring-boot:run
```

如本机路径不同，替换 JAVA_HOME/Maven 路径。启动会执行 `src/main/resources/mysql.sql` 与 `postgresql.sql`：均为 CREATE IF
NOT EXISTS 加仅插入不存在的种子，不重置库存余额。也可以使用 IDEA 的两个数据源分别手动执行这些文件。初始资源为 MySQL p100
库存100、PostgreSQL u100余额10000。

IDEA：导入根 pom，SDK/运行 JRE 设置17，直接运行 `cn.demo.seata.tcc.TccApplication`，工作目录为项目根目录，端口8103。直接
Java：构建后运行 `java -jar seata/tcc-demo/target/seata-tcc-demo-1.0.0.jar`。

## 验证

打开同目录 requests.http，每轮将 `@run = manual-001` 改成新的后缀，所有订单与故障注入共用该后缀；按顺序执行，先保存本轮资源基线。HTTP
Client 断言检查状态码和资源变化。二阶段异步时每秒重跑对应 GET 断言块，最多60秒，不要重跑整份文件；新一轮务必更换
run，否则失败编号会返回原来的取消状态。成功购买 quantity=2/unitPrice=10 后，库存减少2、余额减少20、冻结归零，两个分支均
CONFIRMED。故障 AFTER_ORDER 在 MySQL 本地事务中抛出；AFTER_STOCK 在库存预留之后抛出；AFTER_ACCOUNT 在余额预留之后抛出；所有已预留资源由
TC Cancel 释放。资源不足也触发相同全局回滚。

```sql
-- MySQL
SELECT * FROM seata_tcc_orders;
SELECT * FROM seata_tcc_stock;
SELECT xid,branch_id,order_id,state FROM seata_tcc_order_branch;
-- PostgreSQL
SELECT * FROM seata_tcc_account;
SELECT xid,branch_id,order_id,state FROM seata_tcc_account_branch;
```

POST 返回 COMMIT_REQUESTED 表示提交请求，最终结果以两个分支状态为准。GET `/api/seata/tcc/state` 包含两库快照（
`atomicSnapshot=false`）；两库顺序读取，不是原子快照，TC 回调期间可能观察到一库已完成另一库未完成，请等待重试后重查。REPLAY
返回原业务 XID，也不宣称两个分支已经完成。

相同 orderId 的已确认请求只返回原事务，不重复扣款；业务内容变化返回409。PREPARED/CANCELLED
编号拒绝重新执行；需要查原事务，不可通过换号盲重试。并发重复请求由业务唯一约束兜底，可能返回409。失败发生在订单本地落库前，可能没有订单行，但
TC 分支取消墓碑仍可查询。

二阶段演示故障：先调用 POST `/api/seata/tcc/faults/{orderId}/CONFIRM/2` 或 `/CANCEL/2` 再提交对应订单；回调前两次返回
false，TC 自动重试，直到冻结清零。计数为单 JVM 演示内存状态，重启即清除；业务分支和冻结数据是数据库持久状态。不得在生产中暴露此故障端点。重试由
TC 决定，无需人工调用补偿接口。

## 自动测试

```powershell
$env:DEMO_SEATA_TESTS='true'
mvn -B -s scripts/maven-settings.xml '-Dmaven.repo.local=.runtime/m2' -f seata/tcc-demo/pom.xml test
```

测试连接真实 TC 和两库，使用 UUID 的独立用户、商品和订单，不修改演示种子或其他模块的数据；默认未设置 DEMO_SEATA_TESTS
时跳过。测试覆盖成功、重复业务内容冲突、不同阶段异常、余额不足、库存不足、直接重复二阶段、空取消与晚 Try，以及 TC
二阶段失败重试。测试保留诊断行，便于通过 XID/branch_id 与 TC 日志核对。

幂等与防悬挂采用各库 `(xid,branch_id)` 主键和行锁，空 Cancel 插入 CANCELLED 墓碑，晚 Try 拒绝；重复成功二阶段返回
true，跨终态动作返回 false。显式 `useTCCFence=false`，避免默认 MySQL 本地事务管理器管理 PostgreSQL
分支。故障前的资源变化和分支状态始终同库同事务提交。
