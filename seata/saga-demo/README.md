# Apache Seata Saga 官方持久化状态机

Java 17 / Spring Boot 3.3.13 / Apache Seata 2.0.0，端口 **8104**。单个 JVM 中，MySQL 存订单、库存及官方引擎日志，PostgreSQL
存余额。本例真实使用 `StateMachineEngine`、`DbStateMachineConfig`、`ProcessCtrlStateMachineEngine` 和 JSON 状态语言，不使用
common 的手写 WorkflowEngine。

## 启动与数据库

先按 [根目录说明](../../README.md#seata-server) 准备父 POM/common 和两库。Saga 可独立运行，无需 TC、Nacos、Redis 或
RabbitMQ。

默认 MySQL `127.0.0.1:3308/dtx_demo_mysql`（root），PostgreSQL `127.0.0.1:5432/dtx_demo_pg`
（postgres）。连接读取 [application.yml](src/main/resources/application.yml) 的 `demo.datasource`，本机密码已分别配置为
root / postgres，修改连接直接编辑 YAML，无需数据库环境变量。应用启动自动执行 `src/main/resources/sql/mysql.sql` 和
`postgresql.sql`，只创建
`seata_saga_*` 表、插入缺失的 `p1=100` / `u1=1000`；再次启动不会重置已有资源。SQL 也可在 IDEA 数据库控制台分别执行。引擎表采用官方日志字段结构，表前缀为
`seata_saga_`，时间列保留毫秒以支持官方乐观锁。

从仓库根目录打开 Windows CMD（下列是直接命令，不需要 ps1）：

```bat
mvn -B -s .mvn/settings.xml "-Dmaven.repo.local=.runtime/m2" -pl seata/saga-demo -am package
java -jar seata/saga-demo/target/seata-saga-demo-1.0.0.jar
```

也可先从根目录执行
`mvn -B -s .mvn/settings.xml "-Dmaven.repo.local=.runtime/m2" -pl seata/saga-demo -am install -DskipTests` 安装父
POM/common，再用
`mvn -B -s .mvn/settings.xml "-Dmaven.repo.local=.runtime/m2" -f seata/saga-demo/pom.xml spring-boot:run`
。可选环境变量
`MYSQL_URL / MYSQL_USER / MYSQL_PASSWORD`、`PG_URL / PG_USER / PG_PASSWORD` 与 common DatabaseFactory 一致。

IDEA：Maven 导入根 pom，项目 SDK/运行 JRE 选 Java 17；直接运行 `cn.demo.seata.saga.SagaApplication`，工作目录为项目根目录。打开本目录
`requests.http`，每轮修改 `@run`，执行全部请求；脚本含状态及最终资源断言。重复执行成功订单会消耗资源，若 p1 或 u1
不足，请显式补充本例专用表，切勿重置其他模块数据。

## 实际机制与边界

`statelang/purchase.json` 定义 Order → AfterOrder → Stock → AfterStock → Account → AfterAccount。三个写动作各有对应 Undo
节点，检查点故障通过 Catch 路由到官方 CompensationTrigger。业务 SQL 和动作流水在同一库内 `REQUIRES_NEW`
提交；引擎按反序执行补偿。余额不足等动作异常标记 UN，允许官方引擎对未知结果进行空补偿。

每个动作流水以订单号唯一，锁定流水后更新资源；重复正向与重复补偿均幂等。NEW 空补偿写 COMPENSATED 墓碑，拒绝迟到正向。业务号
payload 包含用户、商品、数量及规范化单价，不包含故障开关。同 payload 重复 POST 返回原实例；内容冲突返回 409。跨 JVM
新建及恢复同一业务号共用 MySQL 连接级 GET_LOCK 串行化，并由官方实例表唯一业务键兜底。恢复入口锁外只用实例定位业务号；取锁后重读状态，确保等待中的成功订单不能被旧状态误补偿。

这里安装的是官方 `DbAndReportTcStateLogStore`，不注入 `SagaTransactionalTemplate`：其源码原生支持模板为 null 时仅持久化、不向
TC 报告。`DbStateMachineConfig` 因已显式设置日志存储，不会创建 TC 模板。`seata.enabled=false` 且不启用数据源代理，因此日志与本地业务不会意外加入
AT。定义、输入参数、状态执行结果和补偿关联均由官方 SQL 日志保存。API Purchase record 转换为仅含 map/string/number
的引擎输入，避免重启后反序列化 record 或 SpEL 属性访问不兼容。

本例演示的是单 JVM 两库的 Saga 最终一致性，不演示 RPC、AT/XA 隔离或 TC
自动恢复。正向事务提交后其他读者能看见中间值；补偿是新业务事务，可能失败。恢复需调用运维接口，无后台自动扫描。故障注入保存在内存中且一次性消费，实际状态及恢复所需参数全部持久化。生产应增加鉴权、扫描调度、告警及日志保留。本示例服务对象串行化本
JVM 的调用，跨实例新建/恢复共用业务号 GET_LOCK；锁等待超时返回 409，可稍后重试。官方日志的时间戳乐观锁另作兜底。

补偿恢复接口仅重试已开始过补偿且已停止的 FA/UN 实例（compensationStatus 非空），已补偿 SU 幂等返回。成功状态 SU
一律拒绝取消；运行中、RU 或尚无补偿记录的正向流程一律拒绝，并提示需要专门恢复/人工诊断。若进程在实例启动后、首个节点前退出，数据库会留下没有节点的
RU 实例；接口保持此事实，绝不会对空日志补偿而产生假 SU。任意位置中断的正向流程恢复不属于本接口支持范围，不能仅凭锁已释放或进程已重启就视为可补偿。

## API 与恢复实验

`POST /api/seata/saga/orders` 接受公共 Purchase；`GET /state` 返回两库资源和动作；`GET /instances/{id}` 返回官方实例状态和节点日志；
`POST /instances/{id}/compensate` 从持久化日志恢复失败补偿。成功实例拒绝此接口取消，业务取消应另建 Saga。

正常完成 `status=SU, compensationStatus=NONE`；失败且已补偿 `status=FA/UN, compensationStatus=SU`。失败的业务请求仍返回
HTTP 200 及完整实例结果，调用方必须检查两个状态；参数错误 400，业务号冲突/成功实例取消 409。实例 ID 为官方引擎 UUID，不要求含
TC 地址。

先安排下一次 ACCOUNT 补偿失败：

```http
POST http://127.0.0.1:8104/api/seata/saga/faults/restart-saga-001/ACCOUNT

POST http://127.0.0.1:8104/api/seata/saga/orders
Content-Type: application/json

{"orderId":"restart-saga-001","userId":"u1","productId":"p1","quantity":2,"unitPrice":10.00,"failAt":"AFTER_ACCOUNT"}
```

保存返回的 `id`，此时 `compensationStatus` 非 SU，账户仍比起点少 20。停止**仅本模块 JVM**并用相同命令重启；查询
`GET /api/seata/saga/instances/{id}` 后调用 `POST /api/seata/saga/instances/{id}/compensate`，应变为 SU，资源恢复起点；再次调用保持
SU，不重复退款。故障注入 `?loseAck=true` 则在本地补偿已经提交后抛异常，重试会读取 COMPENSATED 流水并跳过退款。

可在 MySQL 查询 `seata_saga_state_machine_def`、`seata_saga_state_machine_inst` 及 `seata_saga_state_inst`
，查看状态、start_params、节点补偿关联；PG 的 `seata_saga_account_actions` 展示本地幂等状态。

## 测试

```bat
set DEMO_SEATA_TESTS=true
mvn -B -s .mvn/settings.xml "-Dmaven.repo.local=.runtime/m2" -f seata/saga-demo/pom.xml test
```

11 个真实两库用例：成功、同 payload 幂等与冲突、三个检查点故障、余额不足、库存不足、成功实例拒绝恢复取消、补偿失败后新建引擎恢复、补偿提交丢响应后的幂等恢复、双独立引擎正向/补偿并发互斥、首节点前中断的持久化
RU 实例拒绝补偿。每个用例准备唯一资源，不影响旧示例资源；未设置 gate 时跳过。恢复测试丢弃旧引擎及定义缓存，使用全新
DbStateMachineConfig，直接验证官方持久化重放路径。

官方参考：[Saga 文档](https://seata.apache.org/docs/v2.0/user/mode/saga/)、[2.0.0 源码](https://github.com/apache/incubator-seata/tree/v2.0.0/saga)
。实现还核对了 Maven Central 2.0.0 的 seata-saga-engine / seata-saga-spring / seata-saga-engine-store sources.jar。

## 已验证范围

2026-11-11 最近一次业务代码验收：本模块 11 个 Java 测试通过。官方持久化状态机、补偿重试、丢响应幂等、新引擎恢复及并发互斥；20
个 HTTP 场景通过。实际 JVM 重启后恢复失败补偿，不重复退款。

公共环境、Seata Server 配置及全项目验收汇总见[根 README](../../README.md)。
