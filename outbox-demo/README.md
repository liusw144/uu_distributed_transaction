# 消息表 + 定时补偿：启动与验证

验证入口：[requests.http](requests.http)；自动测试：[OutboxScenarioTest.java](src/test/java/cn/demo/outbox/OutboxScenarioTest.java)。

## 1. 直接运行服务

完成[公共数据库初始化](../README.md)，IDEA 选择 JDK 17，直接运行 `cn.demo.outbox.OutboxApplication`，Working directory
为项目根目录。连接读取 [application.yml](src/main/resources/application.yml) 的 `demo.datasource`：MySQL `3308 root/root`
、PG `5432 postgres/postgres`，无需数据库环境变量。以下 Program arguments 仅用于快速观察死信，可选：

```text
--demo.retry.max-attempts=3 --demo.retry.base-seconds=1 --demo.retry.max-seconds=2
```

或直接运行：

```text
java -jar outbox-demo/target/outbox-demo-1.0.0.jar --demo.retry.max-attempts=3 --demo.retry.base-seconds=1 --demo.retry.max-seconds=2
```

检查 `GET http://127.0.0.1:8082/demo/health`。省略这些参数时默认最多 8 次，间隔指数增长、上限 60 秒，观察死信需要更长时间。

## 2. 事务边界

MySQL 在同一本地事务内提交 PENDING 订单、库存预占和 demo_tasks。定时任务领取事件后，通过 HTTP 调用 /api/outbox/consume。PG 在同一个事务内保存 demo_receipts 和扣款结果：

- PAID：源端确认订单为 PAID。
- REJECTED：源端取消订单并释放库存。
- 网络错误或 ACK 丢失：复用原事件编号重试，PG 回执防止重复扣款。

源端和消费端默认在同一进程中，通过 HTTP 和两个独立的本地事务交互；不需要消息中间件。

## 3. 按 requests.http 的分组逐条验证

新一轮修改 @run。进入下一组前先将当前任务等到 DONE / DEAD。GET /tasks 最多返回最近 100 条，用 id 精确找到当前业务。

### A. ACK 丢失后重试、幂等消费

1. 查询 state，记录账户余额 B 和库存 S。
2. 设置 loseAckNext=1，再提交 outbox-本轮-paid，数量 1、单价 10。
3. 初始响应为 PENDING；重复查询 tasks，直到该任务 DONE。
4. 任务 attempts >= 2、last_response=PAID；订单 PAID、余额 B-10、库存 S-1。
5. 手动重复调用 consume，仍返回 PAID，余额和库存不再变化。
6. 同一业务号换成数量 2 再消费，返回 HTTP 409，原回执不可被覆盖。

### B. 本地提交前异常

提交 outbox-本轮-local-fail，failAt=AFTER_ACCOUNT。这里表示写完消息后、本地提交前抛异常，实际 PG 扣款仍由异步消费完成。

预期 HTTP 409；订单和任务均不存在，库存和余额保持 A 组结束值。

### C. 余额不足后取消订单并释放库存

提交 outbox-本轮-rejected，数量 1、单价 1000000。此场景要求账户余额小于 1000000、至少有 1 件库存。

等待任务 DONE，last_response=REJECTED；订单 CANCELLED、PG 回执 REJECTED，余额不变，预占库存只释放一次，最终仍为 A 组结束时的库存。

### D. 发送失败耗尽 → 对账 → 重放

1. 设置 failNext=100，提交 outbox-本轮-dead。
2. 等待任务 DEAD；查询 receipt 为 NOT_FOUND。
3. 订单保持 PENDING、库存少 1、余额没有变化。
4. 调用 /demo/faults 清零故障，再调用该任务的 replay。
5. 等待任务 DONE，订单 PAID，账户只扣 10 元，库存不再重复扣减。

DEAD 也可能意味着远端已扣款但 ACK 一直丢失，此时 receipt 会是 PAID。应保持原业务号重放确认，不能在结果不明时直接释放库存或退款。

## 4. SQL 核对

MySQL dtx_demo_mysql：

```sql
SELECT order_id,status,quantity,amount FROM demo_orders
WHERE scenario='OUTBOX' ORDER BY created_at DESC LIMIT 20;
SELECT id,status,attempts,last_response,last_error FROM demo_tasks
WHERE task_type='OUTBOX' ORDER BY created_at DESC LIMIT 20;
SELECT id,available FROM demo_stock WHERE id='OUTBOX:SKU-001';
-- 本地失败后，这两条应分别返回 0 行；替换为本轮业务号。
SELECT * FROM demo_orders WHERE order_id='outbox-manual-001-local-fail';
SELECT * FROM demo_tasks WHERE id='outbox-manual-001-local-fail';
```

PG dtx_demo_pg：

```sql
SELECT id,balance FROM demo_accounts WHERE id='OUTBOX:U1001';
SELECT event_id,result FROM demo_receipts
WHERE receipt_type='OUTBOX' ORDER BY created_at DESC LIMIT 20;
```

A～D 全部完成后，相对于本轮最初基线，只有 paid 和 dead 两笔实际扣款：余额 B-20、库存 S-2；rejected 的库存已经释放，local-fail 没有留下业务数据。

## 5. 在本模块运行 JUnit

先停止 OutboxApplication，避免后台工作者抢走测试事件。IDEA 运行 OutboxScenarioTest，公共六项环境变量之外增加 DEMO_DB_TESTS=true。

现有用例覆盖重复消费只扣一次、相同编号内容冲突、余额不足仅补偿一次、本地提交失败不留下订单或任务。测试中定时任务关闭，用测试方法驱动每个确认步骤。

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl outbox-demo -am "-Dtest=OutboxScenarioTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## 已验证范围

2026-11-11 最近一次业务代码验收：本模块 3 个 Java 测试通过。本地事务回滚、重复消费、业务内容冲突和拒绝扣款后的库存补偿；无数据库环境变量启动及两库读取通过。

公共环境、Seata Server 配置及全项目验收汇总见[根 README](../README.md)。
