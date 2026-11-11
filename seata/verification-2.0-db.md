# Seata 2.0.0 + DB 模式验收

日期：2026-11-11。客户端和服务端统一为 **2.0.0**，Java 17，Demo 使用 Spring Boot 3.3.13；服务端使用用户的 D:
/workspace/incubator-seata 源码主类。

## 版本与启动方式

- 根 pom.xml 的 seata.version=2.0.0；原 seata-demo 和新 AT / XA / TCC / Saga 共五个模块均使用 io.seata:
  seata-spring-boot-starter，Java API 与日志配置同步为 io.seata。
- 服务端 application.yml 直接配置 MySQL 127.0.0.1:3308/seata_tc_200，使用 DB 模式。local profile 不覆盖存储模式；MySQL
  驱动显式为 8.0.27。
- 源码工程 .run/Seata Server 2.0 DB.run.xml 为 IDEA Application：io.seata.server.ServerApplication，模块 seata-server，JDK
  17，源码根目录，VM 参数 -Dspring.profiles.active=local，使用 ARGS_FILE。
- 源码 78 个相关 Maven 模块构建成功，02:39:33 完成，耗时 8 分 40 秒；没有运行 Seata 上游自身的全部测试，也没有构建容器镜像。
- 本轮实际验证通过编译后的源码 classes + lib 调用上述主类，未操作 IDEA 窗口点击 Run。IDEA
  运行配置已生成；验证结束释放端口，用户可直接在源码工程选择该配置启动。

源码构建日志：.runtime/seata-source-db-build.log。启动日志：.runtime/seata-source-200-main.out.log，其中 session / lock
store mode 均为 db，7091 控制台 HTTP 返回 200，8091 事务端口正常。

## 真实 DB 协调记录

使用 2.0.0 AT 客户端开启全局事务，执行 MySQL 订单 / 库存分支及 PostgreSQL 账户分支，在二阶段之前直接查询 TC 库，观察到 *
*global=1、branches=2、locks=3**。全局回滚后两库资源恢复，订单删除，分支与锁释放；全局会话由 DB 恢复任务延迟清理。

日志：.runtime/seata-db-probe.log。临时探针准备独立 UUID 资源，结束后清理自身资源，不改变默认演示余额。

实际发现并修复官方 2.0 SQL 的字段宽度限制：本项目业务主键带方案前缀，超过 lock_table.pk 的 36 字符。已扩大为 pk VARCHAR(
128)、row_key VARCHAR(512)，并同步源码 script/server/db/mysql.sql。四张协调表与 distributed_lock 的四条初始化记录均已验证。

## Demo 全量测试

设置公共六项数据库环境变量，以及 DEMO_DB_TESTS=true、DEMO_XA_TESTS=true、DEMO_SEATA_TESTS=true，源码 TC 运行时，从 Demo
根目录执行：

```text
mvn -B verify
```

项目 .mvn/maven.config 已配置 HTTPS Maven settings 和本机依赖缓存。02:57:59 完成，13 个模块全部 SUCCESS，耗时 1 分 44
秒；JUnit XML 汇总 **113 测试、0 失败、0 错误、0 跳过**。

| 范围          | 用例数 |
|-------------|----:|
| 原有六方案及公共协调器 |  77 |
| Seata AT    |   8 |
| Seata XA    |   8 |
| Seata TCC   |   9 |
| Seata Saga  |  11 |

日志：.runtime/seata-200-final-verify.log。SDK 2.0 的 AdapterInvocationWrapper 包装业务异常，测试已改为严格验证根因
BusinessException 及对应业务内容，同时保留两库资源、订单和分支状态断言；没有将任意异常视为正确回滚。独立审查的问题已处理，无重要遗留发现。

XA 日志明确出现 branchType=XA、ConnectionProxyXA 与 ResourceManagerXA 的数据库二阶段提交 / 回滚；AT 和 TCC 同样核对实际分支类型。

## HTTP 行为

- 新四模式 requests.http：AT 12、XA 12、TCC 17、Saga 20，合计 **61 场景全部通过**，包括状态码、资源和最终状态断言。run=verify-1794337189796；日志
  .runtime/seata-200-http.log。
- 原三服务 AT 另验证 **10 个请求**：成功、三个故障点、重复业务号、缺少 XID
  拒绝与实际两库结果均正确。run=at-200-ebe33890-0e65-4d24-8cb0-b871416e6427；日志 .runtime/seata-200-legacy-http.log。

合计 71 个 HTTP 请求。没有运行 ps1。

## 服务端与客户端同时中断后的恢复

TCC 订单 tcc-restart-6de325f2-b7da-42ca-9eff-a3ba7dcc9613，XID 192.168.18.212:8091:7197595574486806575。注入 Confirm
暂时失败后，停止客户端 JVM 和源码 TC JVM。停机期间仍可从 TC 数据库读到全局状态 CommitRetrying（3）和两条 TCC 分支。重启源码
TC 主类及 TCC 客户端，TC 从 DB 恢复并重新下发 Confirm，两分支最终 CONFIRMED，库存与余额只扣一次，冻结归零。

Saga 订单 sg-restart-cd220349-7181-4593-93bb-028bac66f560，官方实例 cf8bf7ce2a57cf046dfe3025975d014e。账户补偿失败后停止
Saga JVM；重启后从持久化实例恢复补偿，资源回到基线，重复恢复不重复退款。Saga 状态机恢复独立于 TC。

日志：.runtime/seata-200-restart.log、.runtime/seata-200-server-restart.out.log。03:08 核对
global_table、branch_table、lock_table 均为 0，TCC 冻结为 0，PostgreSQL 预备事务为 0。

## 使用边界

Saga 的恢复接口只支持已停止的失败补偿，运行中 / 无补偿记录的正向中断实例会明确拒绝，需要单独诊断；不是任意位置强杀后的通用正向恢复器。独立四模式均为单
JVM 跨两库，原 AT 模块另提供三个 JVM 的 HTTP 示例。

本轮验证进程已停止，7091 / 8091 可供 IDEA 启动。数据库、源码编译产物和诊断记录保留，成功演示造成的资源变化不重置。
