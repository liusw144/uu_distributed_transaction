# Seata 四模式本机验收记录

本页为此前 2.2.0 + file 模式的历史验收。当前版本已统一为 2.0.0，服务端改用源码主类和 DB
存储；[最新 2.0.0 + DB 验收](verification-2.0-db.md)，[IDEA 配置](server/README.md)。

日期：2026-11-11。Java 17、Spring Boot 3.3.13、Apache Seata 2.2.0、本机 MySQL 8.0（3308）和 PostgreSQL 16（5432）。使用专用
dtx_demo_mysql / dtx_demo_pg；构建、启动和验证均未调用 ps1 脚本。

## 全工程构建与真实集成测试

先启动本机 TC，停止业务 Demo，设置 MYSQL_URL / MYSQL_USER / MYSQL_PASSWORD、PG_URL / PG_USER / PG_PASSWORD，以及
DEMO_DB_TESTS=true、DEMO_XA_TESTS=true、DEMO_SEATA_TESTS=true，然后从根目录直接执行：

```text
mvn -B -s scripts/maven-settings.xml -Dmaven.repo.local=.runtime/m2 verify
```

02:12:45 完成，13 个 Maven 模块全部 SUCCESS，总耗时 1 分 50 秒。JUnit XML 汇总 **113 个测试，失败 0，错误 0，跳过 0**。

| 范围             | 用例数 |
|----------------|----:|
| 原有六方案及公共持久化协调器 |  77 |
| Seata AT       |   8 |
| Seata XA       |   8 |
| Seata TCC      |   9 |
| Seata Saga     |  11 |
| 合计             | 113 |

AT / XA 使用真正 TC 和两个真实数据库，检查实际代理类型、成功提交、三个故障点、资源不足、重复业务号。TCC 检查真实 TC
回调、预留及释放、幂等、空取消、防悬挂、内容冲突和二阶段失败自动重试。Saga 检查官方 SQL 状态机、业务补偿、补偿失败 /
丢响应、新引擎恢复、跨实例提交与恢复互斥、正向中断实例的安全拒绝。

日志：.runtime/seata-modes-final-verify.log；各模块 target/surefire-reports 为实际 JUnit 报告。

## 模块 HTTP 验证

使用最终构建的四个可执行 JAR，直接启动端口 8101-8104，依序执行每个模块 requests.http，并运行其中 IDEA HTTP Client
的状态码、资源与终态断言。异步二阶段按条件轮询 GET，不重复 POST。

| 模块        | HTTP 场景数 | 结果   |
|-----------|---------:|------|
| at-demo   |       12 | 全部通过 |
| xa-demo   |       12 | 全部通过 |
| tcc-demo  |       17 | 全部通过 |
| saga-demo |       20 | 全部通过 |
| 合计        |       61 | 全部通过 |

本轮 run：verify-1794334454127。日志：.runtime/seata-modes-final-http.log。

## 实际 JVM 中断与重启

以下均使用最终 JAR，先读取资源基线，再停止经过 PID 和启动时间核对的对应 JVM，重启同一应用，保留原数据库及 TC 文件存储。

- TCC：订单 tcc-restart-cd398b2d-2a7e-41f2-a798-6871516043ad。Confirm 故障使两分支保留 PREPARED，库存冻结 1、账户冻结
  7；中断客户端 JVM 后重启，TC 自动重新下发 Confirm。两分支 CONFIRMED，冻结归零，库存及余额只各扣减一次。
- Saga：订单 sg-restart-48f76862-f8b0-4704-89db-c6a1858c1f3e，官方实例 0463ae7d3a7d4e20ad701537602e5bc9。账户补偿失败后中断
  JVM；重启后仍可查询原持久化失败实例，调用 compensate 恢复为 compensationStatus=SU，库存 / 余额恢复到基线；再次补偿不重复退款。

日志：.runtime/seata-modes-final-restart.log。额外的 .runtime/seata-restart-verification.log 是本轮早期中断实验，最终版本以上述
final 日志为准。

## 审查与恢复边界

AT / XA 和 TCC 分别经过独立代码审查；XID 断言已兼容非默认 TC 端口。Saga 审查发现两项恢复入口问题，均已有先红后绿回归及全量验证：

1. 新建与恢复共用数据库业务号锁，取得锁后重新读取状态，阻止另一进程在正向执行中开始补偿。
2. 无补偿记录的 RU / 运行中实例拒绝补偿，避免首个节点前中断留下的空实例被错误标记为购买成功。测试使用官方 ParamsSerializer
   的有效持久化输入，合成空实例由测试按精确 ID 清理。

Saga 此恢复接口仅处理已停止的失败补偿；任意位置中断的正向流程需要专门恢复或人工诊断。四模块均为单 JVM 跨两库示例，未宣称覆盖跨
JVM RPC、所有进程 / TC / 数据库故障时序或生产高可用与吞吐。

## 结束状态

二阶段及补偿恢复结束后，本模块 TCC NEW / PREPARED 分支数为 0、冻结资源为 0，PostgreSQL 预备事务数为 0，Saga
实例均已停止。默认演示资源保留本轮成功购买结果，不重置：

| 模式   |    库存可用 |       账户可用余额 |
|------|--------:|-------------:|
| AT   |      96 |      9960.00 |
| XA   |      96 |      9960.00 |
| TCC  | 90，冻结 0 | 9906.00，冻结 0 |
| Saga |      96 |       960.00 |

后续手工验证请重新读取基线。原有 Demo 数据保持不变；本轮启动的验证应用和 TC 均已停止，运行日志、TC 持久化目录及诊断流水保留。
