# XA 两阶段提交：启动与验证

验证入口：[requests.http](requests.http)；自动测试：[XaScenarioTest.java](src/test/java/cn/demo/xa/XaScenarioTest.java)。

## 1. 启动条件

先完成[根 README 的数据库和环境变量配置](../README.md)。在 PG 执行：

```sql
SHOW max_prepared_transactions;
```

必须大于 0。未启用时，由超级用户执行下面语句，再在 Windows 服务管理器中重启 PostgreSQL；重启会中断该实例的其他连接：

```sql
ALTER SYSTEM SET max_prepared_transactions = 100;
```

参数已经生效就不需要重复修改或重启。本 Demo 不会自动修改实例参数。

## 2. 直接运行服务

IDEA 运行 `cn.demo.xa.XaApplication`，Working directory 为项目根目录，填写公共六项数据库环境变量，无需额外 Program arguments。

或在根目录直接运行已打包的 jar：

```text
java -jar xa-demo/target/xa-demo-1.0.0.jar
```

检查 `GET http://127.0.0.1:8081/demo/health`。

MySQL 和 PG 分别使用 XADataSource，由 Atomikos JTA 将订单、库存和扣款纳入同一事务。业务异常时由 JTA 回滚两个数据库。

## 3. 逐步执行 requests.http

先修改文件顶部 @run，然后按序执行：

| 步骤 | 操作 | 预期结果 |
|---|---|---|
| 1 | 查询 /api/xa/state | 记录 XA:U1001 余额 B、XA:SKU-001 库存 S |
| 2 | 成功下单，1 件、单价 10 | HTTP 200，status=PAID |
| 3 | 再次查询状态 | 成功订单存在，余额 B-10，库存 S-1 |
| 4 | AFTER_ORDER 异常 | HTTP 409，失败订单不存在 |
| 5 | AFTER_STOCK 异常 | HTTP 409，库存恢复 |
| 6 | AFTER_ACCOUNT 异常 | HTTP 409，两库写入全部回滚 |
| 7 | 查询状态 | 仍只有成功订单，余额 B-10、库存 S-1 |
| 8 | 重复提交成功订单的业务号 | HTTP 409，不重复扣库存或余额 |
| 9 | 最终查询状态 | 与步骤 3 一致 |

不要只根据错误响应判断回滚成功，应同时检查失败订单、库存和余额。

## 4. 数据库核对

MySQL dtx_demo_mysql：

```sql
SELECT order_id,status,quantity,amount FROM demo_orders
WHERE scenario='XA' ORDER BY created_at DESC LIMIT 20;
SELECT id,available FROM demo_stock WHERE id='XA:SKU-001';
-- 将下方业务号后缀替换为 requests.http 中的 @run；预期 0 行。
SELECT * FROM demo_orders WHERE order_id IN
('xa-manual-001-after-order','xa-manual-001-after-stock','xa-manual-001-after-account');
```

PG dtx_demo_pg：

```sql
SELECT id,balance FROM demo_accounts WHERE id='XA:U1001';
SELECT gid,prepared,owner,database FROM pg_prepared_xacts
WHERE database='dtx_demo_pg';
```

正常完成后，此 Demo 不应留下悬而未决的 prepared transaction。不要手工处理未知来源的 prepared transaction。

## 5. 在本模块运行 JUnit

IDEA 直接运行 XaScenarioTest，环境变量使用公共六项，并增加 DEMO_XA_TESTS=true：

- commitsBothRealXaResources：订单 PAID、库存 10→8、余额 100→80。
- failureAfterDebitRollsBackBothDatabases：订单 0 行、库存仍为 10、余额仍为 100。

也可在根目录执行：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl xa-demo -am "-Dtest=XaScenarioTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

测试使用独立随机业务号和测试行，结束后清理。运行前停止 XaApplication。

## 6. 恢复日志

应用日志目录为根目录下 .runtime/xa-logs，测试使用本模块 target/xa-test-logs。崩溃恢复时保留相同日志目录、管理器名和资源名；本配置只启动一个 XA 应用实例。HTTP 场景验证业务异常回滚，不等同于验证进程断电恢复。
