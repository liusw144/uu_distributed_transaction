# 本机验证记录

> 本文为 2026-11-07 的历史验证记录。当前验证步骤与请求已迁入 [XA](../xa-demo/README.md)、[Outbox](../outbox-demo/README.md)、[Seata AT](../seata-demo/README.md)、[通知](../notification-demo/README.md)，可直接运行各模块 HTTP 请求或 JUnit；下文旧脚本命令仅记录当时的执行方式。

验证日期：2026-11-07。使用本机 JDK 17、MySQL 8.0.46（3308）、PostgreSQL 16（5432），实际操作专用库 `dtx_demo_mysql`、`dtx_demo_pg`。

## 构建与数据库测试

命令：`./scripts/build.ps1 -DatabaseTests -XaTests`。

最终结果：六个 Maven 模块全部 BUILD SUCCESS；15 项测试，0 failures、0 errors、0 skipped。

| 用例组 | 数量 | 验证内容 |
|---|---:|---|
| PurchaseTest | 2 | 精确金额、负数量和小数位校验 |
| RetryPolicyTest | 1 | 有界指数退避与重试耗尽 |
| DatabaseContractTest | 3 | MySQL 本地回滚、PG 条件扣款、过期租约与最后一次崩溃 |
| XaScenarioTest | 2 | 两个真实 XA 资源提交、扣款后异常时两库回滚 |
| OutboxScenarioTest | 3 | 重复消费、内容冲突、余额不足只补偿一次、提交前回滚 |
| XidFilterTest | 1 | 远程分支异常后清理线程 XID |
| PostgresUndoLogSchemaTest | 1 | 实际 Seata PG Undo Log 适配器与建表结构兼容 |
| NotificationScenarioTest | 2 | 通知回执幂等、支付事实和任务原子落库 |

构建日志保留在 `.runtime/build-final2.log`，JUnit 原始报告位于各模块 `target/surefire-reports`。

## 完整 HTTP 验证

启动真实 Seata Server 2.2.0、三个 AT 独立进程、XA、Outbox、通知应用。

命令：`./scripts/smoke.ps1 -IncludeXa -IncludeAt`。

实际输出：

```text
PASS Outbox：真实 HTTP、丢失 ACK、重试、幂等扣款
PASS Outbox：本地回滚、余额不足、订单取消和库存补偿
PASS 最大努力通知：故障、退避重试、业务 ACK、回执幂等
PASS xa：跨库提交和扣款后全局回滚
PASS at：跨库提交和扣款后全局回滚
```

证据：`.runtime/smoke-all.log`。每个方案的演示订单、任务和回执保留在数据库中，接口可以直接查询。

## 死信、对账与重放

临时以 `RETRY_MAX_ATTEMPTS=3` 启动通知应用，执行 `./scripts/dead-letter.ps1 -TimeoutSeconds 30`。

```text
PASS 死信及重放：ACK丢失=False，原投递次数=3，原回执=NOT_FOUND
PASS 死信及重放：ACK丢失=True，原投递次数=3，原回执=ACK
```

两个场景均验证 DEAD 后的回执查询、原事件编号重放与有效 ACK；ACK 丢失时原事件已持久化，重放仍只保留一份回执。验证后通知服务恢复默认 8 次重试。证据：`.runtime/dead-letter.log`。

## 环境与审查

- 用户明确授权修改并重启 PG；`SHOW max_prepared_transactions` 已确认结果为 100。
- Seata 官方归档 SHA512 已匹配：`6f187268e2adb5667bda37820f67d92c6915accf91ca75ff171e13136bde64495246fee64b1fb16fffde8f0483c8110d093dba905bb073bedebc8d1d31705030`。
- 独立只读审查发现的 PG Undo Log 序列字段缺失已经通过先失败后通过的实际适配器测试修复。
- PowerShell 脚本均通过解析器语法检查，建库、启动、停止、Seata 安装校验及 HTTP 脚本已实际运行。
- 四个方案共用全局业务号命名空间，此约束已写入 README。

本次验证涵盖正常提交、业务异常回滚及通知故障恢复；没有进行数据库断电、XA 协调器崩溃或 Seata TC 崩溃后的恢复压测。恢复日志和固定资源标识已保留，相关机制及限制见 README。

## 2026-11-08：各模块独立验证入口

按四个模块 README 直接启动 Java 进程，并执行对应 requests.http；未调用 .ps1 文件。直接 Maven verify 通过 15 项测试，0 失败、0 错误、0 跳过。HTTP 共 71 个请求：XA 9、Outbox 25、Seata AT 10、通知 27，均通过事务结果与故障恢复断言。验证后停止了本次启动的进程。

构建日志：.runtime/validation-by-module-build.log；HTTP 结果：.runtime/validation-by-module-http.log。测试数据业务号后缀为 verify-1794070467965。
