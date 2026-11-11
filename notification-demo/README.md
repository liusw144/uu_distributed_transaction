# 最大努力通知：启动与验证

验证入口：[requests.http](requests.http)；自动测试：[NotificationScenarioTest.java](src/test/java/cn/demo/notification/NotificationScenarioTest.java)。

## 1. 直接运行服务

完成[公共数据库初始化](../README.md)，IDEA 选择 JDK 17，直接运行 `cn.demo.notification.NotificationApplication`，Working
directory 为项目根目录。连接读取 [application.yml](src/main/resources/application.yml) 的 `demo.datasource`：MySQL
`3308 root/root`、PG `5432 postgres/postgres`，无需数据库环境变量。以下 Program arguments 仅用于快速观察重试和死信，可选：

```text
--demo.retry.max-attempts=3 --demo.retry.base-seconds=1 --demo.retry.max-seconds=2
```

或直接运行：

```text
java -jar notification-demo/target/notification-demo-1.0.0.jar --demo.retry.max-attempts=3 --demo.retry.base-seconds=1 --demo.retry.max-seconds=2
```

检查 `GET http://127.0.0.1:8086/demo/health`。省略重试参数时，默认最多 8 次、指数退避上限 60 秒。

本方案假定支付平台已确认支付成功，在一个 MySQL 本地事务中记录 PAID 订单和通知任务。它只演示通知送达，不扣账户余额和库存，不能替代 XA / AT 的原子支付。

## 2. 按 requests.http 的分组逐条验证

新一轮修改顶部 @run。每组完成后再运行下一组；异步查询可重复点击，等待目标任务完成。

### A. 接收端故障、ACK 丢失和重复通知

1. 设置 failNext=1、loseAckNext=1。
2. 提交 notify-本轮-retry，返回支付状态 PAID、通知状态 PENDING。
3. 第一次投递被接收端拒绝；第二次回执已提交但返回错误，模拟 ACK 丢失；第三次返回原有 ACK。
4. 重复查询 tasks，直到该任务 DONE，attempts >= 3、last_response=ACK。
5. 查询 receipt 返回 ACK；重复调用 callback 仍返回 ACK，PG 中仅一份回执。

### B. 本地业务提交前失败

提交 notify-本轮-local-fail，failAt=AFTER_ORDER。

预期 HTTP 409；MySQL 中支付订单和通知任务都不存在，PG 也没有该通知回执。

### C. 一直未送达的死信与重放

1. 设置 failNext=100，提交 notify-本轮-dead-before。
2. 等待 DEAD，查询回执为 NOT_FOUND；支付订单仍然 PAID。
3. 清零故障，使用原业务号调用 replay。
4. 等待 DONE，last_response=ACK；PG 回执最终只有一条。

### D. 已送达但 ACK 丢失的死信与重放

1. 设置 loseAckNext=100，提交 notify-本轮-dead-ack。
2. 等待 DEAD，查询回执已经是 ACK；不能把 DEAD 理解为对方没有处理。
3. 清零故障，使用原业务号调用 replay。
4. 等待 DONE；接收端返回原 ACK，PG 回执仍然只有一条。

重放只允许对 DEAD 任务执行，恢复时会重置本轮尝试次数，但事件编号和 payload 不变。故障模拟计数重启后清零，任务和回执持久化。

## 3. SQL 核对

MySQL dtx_demo_mysql：

```sql
SELECT order_id,status FROM demo_orders
WHERE scenario='NOTIFY' ORDER BY created_at DESC LIMIT 20;
SELECT id,status,attempts,last_response,last_error FROM demo_tasks
WHERE task_type='NOTIFY' ORDER BY created_at DESC LIMIT 20;
SELECT * FROM demo_orders WHERE order_id='notify-manual-001-local-fail';
SELECT * FROM demo_tasks WHERE id='notify-manual-001-local-fail';
```

PG dtx_demo_pg：

```sql
SELECT event_id,result FROM demo_receipts
WHERE receipt_type='NOTIFY' ORDER BY created_at DESC LIMIT 20;
-- 替换为本轮业务号；经过 ACK 丢失和重放后仍应为 1。
SELECT count(*) FROM demo_receipts
WHERE event_id='notify-manual-001-dead-ack';
```

C、D 两组中 DEAD 阶段的差别是有没有接收端回执；两组最终都会进入 DONE，支付订单始终保持 PAID。

## 4. 在本模块运行 JUnit

先停止 NotificationApplication，避免定时任务抢占测试数据。IDEA 运行 NotificationScenarioTest，填写公共六项环境变量并增加 DEMO_DB_TESTS=true。

- repeatedCallbackHasOneDurableReceipt：重复回调只留下一个持久化回执。
- localFailureRollsBackPaymentAndNotificationTogether：业务异常后订单与通知任务全部回滚。

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl notification-demo -am "-Dtest=NotificationScenarioTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

HTTP 场景覆盖真实后台重试与死信，JUnit 验证本地事务和幂等回执；公共模块的 DatabaseContractTest 还覆盖租约过期和迟到工作者隔离。
