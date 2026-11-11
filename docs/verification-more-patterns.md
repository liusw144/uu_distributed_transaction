# TCC / Saga 增补验证记录

日期：2026-11-11。环境：JDK 17、本机 MySQL 8.0（3308）、PostgreSQL（5432），专用 dtx_demo_mysql / dtx_demo_pg。没有使用 ps1
执行构建、启动或验证。

## 全量构建

直接执行 Maven verify，八个 Maven 模块全部成功。

| 测试范围                      | 用例数 | 失败 / 错误 / 跳过 |
|---------------------------|----:|--------------|
| 原有四方案及公共基础                |  15 | 0 / 0 / 0    |
| 持久化协调器 WorkflowEngineTest |  13 | 0 / 0 / 0    |
| TccParticipantTest        |  23 | 0 / 0 / 0    |
| SagaParticipantTest       |  26 | 0 / 0 / 0    |
| 合计                        |  77 | 0 / 0 / 0    |

日志：.runtime/more-patterns-final-build.log；各模块 target/surefire-reports 保留原始 JUnit 报告。

## 模块 HTTP 验证

直接启动 tcc-demo（8092）和 saga-demo（8093），按模块 requests.http 执行并断言数据库业务效果。TCC 使用最多 3 次重试，Saga 使用最多
2 次重试，以快速观察故障恢复。

- TCC：20 个请求场景，验证冻结、手动确认/取消、确认后拒绝取消、业务失败释放、二阶段失败和 ACK 丢失后的幂等恢复；库存与账户
  available + reserved + confirmed 守恒。
- Saga：25 个请求场景，验证成功、重复与内容冲突、各阶段业务失败、余额不足、逆序补偿、ACK 丢失与 blocked 后人工恢复。
- 合计 45 个请求场景全部通过；异步场景轮询等待终态后再执行后续请求。

本轮业务号后缀：verify-1794331552758。日志：.runtime/more-patterns-http.log。演示订单、协调日志和分支流水保留，可使用各模块
README 中的 SQL 观察。

## 实际进程中断后的恢复

1. TCC 在库存分支已经 Confirm、其 ACK 丢失时停止 Java 进程。重启后使用原协调日志自动继续 CONFIRMING，最终 SUCCEEDED；库存只消耗
   1 件、余额只消耗 10 元，冻结归零。
2. Saga 在退款已经提交、其 ACK 丢失时停止 Java 进程。重启后继续 COMPENSATING，最终 COMPENSATED；没有重复退款，库存恢复到本组基线。

对应业务号：

- tcc-restart-32611500382c42498af7d76bfa69f995
- saga-restart-56b8d8b1d7174abaa2501cab8af1c082

日志：.runtime/more-patterns-restart.log。以上为应用进程中断，不等同于数据库断电或跨机网络分区演练。

## 独立审查与回归修复

独立只读审查发现：迟到协调调用可能吸收最新状态并覆盖已经持久化的决议。已先复现、再修复，并通过以下测试：

- staleCoordinatorCannotReplacePersistedConfirmWithCancel
- staleSagaCannotMarkCompensatingWorkflowSuccessful
- confirmDecisionCannotUseStaleExpirySnapshot
- sqlEquivalentIdsUseTheSameSessionLock

Store 层限制合法状态边、完成来源与原子截止时间；Engine 重读后检查固定正向状态；锁键使用数据库返回的规范业务号。另有
deadlineExpiringDuringTryCannotBecomeCommitDecision 覆盖 Try 期间跨过截止时间。

## 实现边界

这是单进程跨库的教学协调器，不是 Seata TCC / Saga 引擎集成。命名锁占用一条 MySQL 连接，默认连接池规模为
8；大量并发驱动可能等待连接，本次未验证生产吞吐或高可用指标。参与者的原子日志、状态 CAS 与幂等仍需保留，不能把会话锁当作唯一正确性保障。

各模块数据库 SQL 是增量初始化；验证结束后停止本次创建的 Java 进程，不停止其他服务，不删除演示业务记录。
