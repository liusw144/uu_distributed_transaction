# Seata AT：三个独立服务的启动与验证

验证入口：[requests.http](requests.http)。本方案运行一个 TC 和订单、库存、账户三个业务进程。

## 1. 准备 TC

完成[公共数据库和环境配置](../README.md)，确认两库已执行 SQL，包含 undo_log 表。PG 的 undo_log 必须包含 id 和 undo_log_id_seq，不能直接照搬 MySQL 表结构。

客户端现已统一为 io.seata:seata-spring-boot-starter:2.0.0。IDEA 打开源码 D:/workspace/incubator-seata，选择 **Seata Server
2.0 DB** 配置，直接运行 io.seata.server.ServerApplication。源码已配置 MySQL 的 seata_tc_200
协调库；完整步骤见 [源码 Server 说明](../seata/server/README.md)。

日志出现 session / lock store mode: db 与 service listen port: 8091 后再启动业务服务。控制台为 http://127.0.0.1:7091，账号
seata/seata；事务协调地址为 127.0.0.1:8091。DB 模式恢复依赖 TC 库，不再使用旧 .runtime/seata 的 2.2.0 file 模式发行包。

## 2. IDEA 启动三个独立进程

复制三个 Application 运行配置，Main class 均为 `cn.demo.at.SeataApplication`，Working directory 均为项目根目录，分别填写公共六项数据库环境变量。

| 配置名称 | Program arguments |
|---|---|
| at-stock | --demo.role=stock --server.port=8084 --spring.application.name=seata-stock |
| at-account | --demo.role=account --server.port=8085 --spring.application.name=seata-account |
| at-order | --demo.role=order --server.port=8083 --spring.application.name=seata-order |

先启动 stock、account，最后启动 order。依次访问 8084、8085、8083 的 /demo/health。

或者在根目录三个独立终端中执行：

```text
java -jar seata-demo/target/seata-demo-1.0.0.jar --demo.role=stock --server.port=8084 --spring.application.name=seata-stock
java -jar seata-demo/target/seata-demo-1.0.0.jar --demo.role=account --server.port=8085 --spring.application.name=seata-account
java -jar seata-demo/target/seata-demo-1.0.0.jar --demo.role=order --server.port=8083 --spring.application.name=seata-order
```

不要在同一终端顺序等待这三条前台命令退出，应每个终端启动一条。默认 TC 地址为 127.0.0.1:8091。

## 3. 按 requests.http 逐步验证

修改 @run，先查询 state，记录 AT:U1001 余额 B、AT:SKU-001 库存 S。

| 场景 | 预期结果 |
|---|---|
| 成功下单，数量 1、单价 10 | 返回 PAID 和非空 xid；余额 B-10、库存 S-1 |
| AFTER_ORDER 异常 | 错误响应；订单分支回滚，不留下失败订单 |
| AFTER_STOCK 异常 | 错误响应；订单和库存分支回滚 |
| AFTER_ACCOUNT 异常 | 错误响应；三个已本地提交的分支全局回滚，余额也恢复 |
| 重复提交成功订单 | 错误响应，不重复扣款和库存 |
| 直接请求库存分支但不带 XID | HTTP 409，拒绝执行，库存不变 |

最终只保留本轮成功订单，余额 B-10、库存 S-1。错误响应可能是 409，也可能因远程调用异常表现为 5xx，**两库最终状态才是回滚依据**。

AT 回滚可能经过 TC 重试；查询若看到中间态，稍后重复查询，并结合 TC 与三个服务的日志检查。普通 SQL 查询可能观察到分支本地提交的中间数据，不要通过管理工具修改仍在全局事务中的业务行。

## 4. 数据库核对与原理对应

MySQL dtx_demo_mysql：

```sql
SELECT order_id,status,quantity,amount FROM demo_orders
WHERE scenario='AT' ORDER BY created_at DESC LIMIT 20;
SELECT id,available FROM demo_stock WHERE id='AT:SKU-001';
SELECT xid,branch_id,log_status FROM undo_log;
-- 替换为本轮后缀，失败订单预期 0 行。
SELECT * FROM demo_orders WHERE order_id IN
('at-manual-001-after-order','at-manual-001-after-stock','at-manual-001-after-account');
```

PG dtx_demo_pg：

```sql
SELECT id,balance FROM demo_accounts WHERE id='AT:U1001';
SELECT id,xid,branch_id,log_status FROM undo_log;
```

AtOrderService 的 @GlobalTransactional 开启全局事务；HTTP 请求头 TX_XID 传递事务编号。XidFilter 在 finally 清理线程变量，AtBranches 使用对应的 mysqlTx / pgTx 提交本地分支，所有 JDBC 写入经过显式 DataSourceProxy。

第一阶段业务数据与 Undo Log 同时提交，二阶段失败使用 Undo Log 补偿回滚；成功提交的 Undo Log 会异步清理，不能要求请求返回瞬间表就为空。

## 5. 在本模块运行 JUnit

IDEA 直接运行 [XidFilterTest](src/test/java/cn/demo/at/XidFilterTest.java) 和 [PostgresUndoLogSchemaTest](src/test/java/cn/demo/at/PostgresUndoLogSchemaTest.java)。前者检查异常后的 XID 清理，后者使用实际 Seata PG 适配器写入 Undo Log 并回滚测试数据。

填写公共六项数据库环境变量及 DEMO_DB_TESTS=true：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl seata-demo -am "-Dtest=XidFilterTest,PostgresUndoLogSchemaTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

这两个测试不需要运行 TC；它们不替代本页三个业务服务的跨库 HTTP 验证。AT 不要求 PostgreSQL 启用 XA prepared transaction 参数。
