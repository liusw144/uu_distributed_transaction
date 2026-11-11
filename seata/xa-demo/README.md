# Seata XA：MySQL 与 PostgreSQL

这是实际接入 Apache Seata 2.0.0 的 XA 模式。一个应用访问两个独立数据库，由 Seata TC 协调；本例不演示跨 JVM RPC。旧的三服务
AT 示例仍保留在根目录 seata-demo。

MySQL、PostgreSQL 均使用 DataSourceProxyXA。第一阶段执行数据库 XA prepare，第二阶段由 Seata TC 发出 commit / rollback。本例不使用
Atomikos，也不依赖 undo_log；预备事务期间数据库仍占用资源锁。

验证入口：[requests.http](requests.http)
；真实框架测试：[XaIntegrationTest.java](src/test/java/cn/demo/seata/xa/XaIntegrationTest.java)。

## 1. 初始化与启动

1. 使用 MySQL 8（3308）和 PostgreSQL（5432）。专用库 dtx_demo_mysql / dtx_demo_pg
   应已存在。分别在对应数据库执行本模块 [MySQL SQL](sql/mysql.sql)、[PostgreSQL SQL](sql/postgresql.sql)
   。重复执行不会重置资源；SEATA_XA:SKU-001 初始库存 100，SEATA_XA:U1001 初始余额 10000.00。
2. 按 [根目录说明](../../README.md#seata-server) 在 IDEA 中运行源码 TC，默认 127.0.0.1:8091，不需要 Nacos、Redis 或
   RabbitMQ。
3. IDEA 使用 JDK 17，直接运行 **cn.demo.seata.xa.SeataXaApplication**
   ，工作目录为项目根目录。连接信息已在 [application.yml](src/main/resources/application.yml) 的 demo.datasource.mysql /
   postgresql 中配置，不需要独立的本地运行配置或数据库环境变量。

| 数据库        | YAML 中的本机默认值                                 |
|------------|----------------------------------------------|
| MySQL      | 127.0.0.1:3308/dtx_demo_mysql，root/root      |
| PostgreSQL | 127.0.0.1:5432/dtx_demo_pg，postgres/postgres |

两个原始连接池通过 @ConfigurationProperties 直接绑定 YAML。`${MYSQL_PASSWORD:root}` 等写法中的冒号后为默认值，未设置环境变量时也会读取
root；环境变量仅用于可选覆盖。Seata Server 的 store.db.password 只用于 TC 库，业务连接配置在本模块的 YAML 中。

默认端口 8102。可通过 SEATA_ADDRESS / SERVER_PORT 环境变量修改 TC 地址和服务端口。

PostgreSQL 还必须允许预备事务：

```sql
SHOW
max_prepared_transactions;
SELECT gid, prepared
FROM pg_prepared_xacts;
```

本机此前已配置为 100；若新环境显示 0，由数据库管理员执行 ALTER SYSTEM SET max_prepared_transactions=100 并重启
PostgreSQL。应用会在启动时检查。不要随意提交或回滚其他应用的预备事务。

也可从根目录构建后直接启动，终端使用 JDK 17：

```text
mvn -B -s .mvn/settings.xml "-Dmaven.repo.local=.runtime/m2" -pl seata/xa-demo -am -DskipTests package
java -jar seata/xa-demo/target/seata-xa-demo-1.0.0.jar
```

## 2. 在模块内逐项验证

用 IDEA HTTP Client 按顺序执行 requests.http。每轮修改 @run，使用新业务号；先查询基线再提交订单，文件内断言会验证结果。

| 场景             | HTTP / 数据库预期                         |
|----------------|--------------------------------------|
| 成功：数量 2、单价 10  | 200，返回真实 XID；订单 PAID，库存 -2，余额 -20    |
| AFTER_ORDER    | 409，MySQL 本地事务回滚；账户未调用               |
| AFTER_STOCK    | 409，MySQL 第一阶段已完成，TC 回滚；订单不存在，资源恢复   |
| AFTER_ACCOUNT  | 409，两个数据库第一阶段已完成，TC 回滚；订单不存在，库存与余额恢复 |
| 余额 / 库存不足      | 409，之前完成的分支也恢复                       |
| 重复业务号 / 改金额后重试 | 409，不重复扣减，也不撤销此前成功订单                 |
| 数量 0 等非法请求     | 400，不触发资源扣减                          |

业务号是全工程 demo_orders 的主键。请求失败或响应丢失后先按原业务号查询，不要换号盲目重试。本例重复请求采用返回冲突策略。

请求结果可以通过 GET /api/seata/xa/state 观察；两次数据库查询不构成跨库一致快照。二阶段恢复期间应等待 TC 重试完成后再观察最终值。

## 3. SQL 核对

```sql
-- MySQL
SELECT *
FROM demo_orders
WHERE scenario = 'SEATA_XA'
ORDER BY created_at DESC;
SELECT *
FROM demo_stock
WHERE id = 'SEATA_XA:SKU-001';

-- PostgreSQL
SELECT *
FROM demo_accounts
WHERE id = 'SEATA_XA:U1001';
SELECT gid, prepared
FROM pg_prepared_xacts;
```

正常完成后不应遗留本模块 XA 预备事务；服务或 TC 故障时保留 TC 协调数据库 seata_tc_200，交给原协调器恢复。

## 4. 真实集成测试

TC 与两个数据库须已启动。测试沿用 YAML 中的本机默认连接，只需在 IDEA 或终端设置 DEMO_SEATA_TESTS=true 后执行：

```text
mvn -B -s .mvn/settings.xml "-Dmaven.repo.local=.runtime/m2" -pl seata/xa-demo -am test
```

测试使用独立业务号与资源，验证实际 Seata 代理、成功、三个故障点、余额不足、库存不足与重复提交。未设置开关时数据库/TC 测试会跳过。

参考：[Seata XA 官方说明](https://seata.apache.org/docs/v2.0/user/mode/xa/)。

## 已验证范围

2026-11-11 最近一次业务代码验收：本模块 8 个 Java 测试通过。真实 DataSourceProxyXA、成功提交、故障回滚及资源不足；12 个 HTTP
场景和直接 YAML 主类启动通过。

公共环境、Seata Server 配置及全项目验收汇总见[根 README](../../README.md)。
