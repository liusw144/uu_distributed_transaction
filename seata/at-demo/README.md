# Seata AT：MySQL 与 PostgreSQL

这是实际接入 Apache Seata 2.0.0 的 AT 模式。一个应用访问两个独立数据库，由 Seata TC 协调；本例不演示跨 JVM RPC。旧的三服务
AT 示例仍保留在根目录 seata-demo。

MySQL、PostgreSQL 均使用 DataSourceProxy。第一阶段在各自本地事务中提交业务 SQL 与 Undo Log；TC 成功后清理日志，失败后读取
Undo Log 恢复。普通数据库查询可能读到第一阶段中间态，写隔离依赖遵守全局锁协议。

验证入口：[requests.http](requests.http)
；真实框架测试：[AtIntegrationTest.java](src/test/java/cn/demo/seata/at/AtIntegrationTest.java)。

## 1. 初始化与启动

1. 使用 MySQL 8（3308）和 PostgreSQL（5432）。专用库 dtx_demo_mysql / dtx_demo_pg
   应已存在。分别在对应数据库执行本模块 [MySQL SQL](sql/mysql.sql)、[PostgreSQL SQL](sql/postgresql.sql)
   。重复执行不会重置资源；SEATA_AT:SKU-001 初始库存 100，SEATA_AT:U1001 初始余额 10000.00。
2. 按 [Seata 总入口](../README.md) 直接用 Java 启动 TC，默认 127.0.0.1:8091，不需要 Nacos、Redis 或 RabbitMQ。
3. IDEA 选择 JDK 17，运行 cn.demo.seata.at.AtApplication，工作目录为项目根目录，填入以下环境变量：

```text
MYSQL_URL=jdbc:mysql://127.0.0.1:3308/dtx_demo_mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USER=root
MYSQL_PASSWORD=root
PG_URL=jdbc:postgresql://127.0.0.1:5432/dtx_demo_pg
PG_USER=postgres
PG_PASSWORD=postgres
```

默认端口 8101。可通过 SEATA_ADDRESS / SERVER_PORT 环境变量修改 TC 地址和服务端口。

AT 的两个数据库都必须有 undo_log 表。本模块 PostgreSQL SQL 包含 BIGSERIAL id，对应 Seata 2.2 适配器使用的 undo_log_id_seq。

也可从根目录构建后直接启动，终端需已配置 JDK 17 和以上环境变量：

```text
mvn -B -s scripts/maven-settings.xml -Dmaven.repo.local=.runtime/m2 -pl seata/at-demo -am -DskipTests package
java -jar seata/at-demo/target/seata-at-demo-1.0.0.jar
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

请求结果可以通过 GET /api/seata/at/state 观察；两次数据库查询不构成跨库一致快照。AT 异步回滚时应等待 TC 重试完成后再观察最终值。

## 3. SQL 核对

```sql
-- MySQL
SELECT * FROM demo_orders WHERE scenario='SEATA_AT' ORDER BY created_at DESC;
SELECT * FROM demo_stock WHERE id='SEATA_AT:SKU-001';
SELECT xid,branch_id,log_status FROM undo_log;
-- PostgreSQL
SELECT * FROM demo_accounts WHERE id='SEATA_AT:U1001';
SELECT xid,branch_id,log_status FROM undo_log;
```

Undo Log 在事务结束后通常会被自动删除，不能用查不到日志来判断是否未接入 AT；测试还会核对实际数据源代理类型和两库回滚。

## 4. 真实集成测试

TC 与两个数据库须已启动，并设好数据库环境变量。在 IDEA 运行测试时额外设置 DEMO_SEATA_TESTS=true；终端可设置同名环境变量后执行：

```text
mvn -B -s scripts/maven-settings.xml -Dmaven.repo.local=.runtime/m2 -pl seata/at-demo -am test
```

测试使用独立业务号与资源，验证实际 Seata 代理、成功、三个故障点、余额不足、库存不足与重复提交。未设置开关时数据库/TC 测试会跳过。

参考：[Seata AT 官方说明](https://seata.apache.org/docs/v2.0/user/mode/at/)。

