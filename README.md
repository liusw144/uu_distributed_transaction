# 分布式事务一致性：六种方案 Demo

使用 JDK 17、Spring Boot 3.3.13、MySQL 8.0、PostgreSQL、Atomikos 6.0.0 和 Seata 2.0.0。验证说明、HTTP 请求和 Java
测试均放在对应服务模块中，可直接在 IDEA 运行。

新增 [seata/：Seata 四种模式完整集成](seata/README.md)，包含 AT（8101）、XA（8102）、TCC（8103）、Saga（8104）。这些模块使用真正的
Seata 代理、TC 或官方状态机引擎；下表保留原有六种方案，便于对比不同框架与手写实现。

| 方案           | 验证说明                                                       | HTTP 请求                                          | 启动类 / 端口                                                                               |
|--------------|------------------------------------------------------------|--------------------------------------------------|----------------------------------------------------------------------------------------|
| XA 两阶段提交     | [xa-demo/README.md](xa-demo/README.md)                     | [requests.http](xa-demo/requests.http)           | XaApplication / 8081                                                                   |
| 本地消息表 + 定时补偿 | [outbox-demo/README.md](outbox-demo/README.md)             | [requests.http](outbox-demo/requests.http)       | OutboxApplication / 8082                                                               |
| Seata AT     | [seata-demo/README.md](seata-demo/README.md)               | [requests.http](seata-demo/requests.http)        | SeataApplication / SeataStockApplication / SeataAccountApplication（8083 / 8084 / 8085） |
| 最大努力通知       | [notification-demo/README.md](notification-demo/README.md) | [requests.http](notification-demo/requests.http) | NotificationApplication / 8086                                                         |
| TCC 资源预留     | [tcc-demo/README.md](tcc-demo/README.md)                   | [requests.http](tcc-demo/requests.http)          | TccApplication / 8092                                                                  |
| Saga 业务补偿    | [saga-demo/README.md](saga-demo/README.md)                 | [requests.http](saga-demo/requests.http)         | SagaApplication / 8093                                                                 |

## 一、数据库准备

使用 Navicat、DBeaver 或数据库客户端连接本机数据库：

| 数据库 | 地址 | 用户 | 专用演示库 |
|---|---|---|---|
| MySQL 8.0 | 127.0.0.1:3308 | root | dtx_demo_mysql |
| PostgreSQL | 127.0.0.1:5432 | postgres | dtx_demo_pg |

首次运行，先在 MySQL 执行：

```sql
CREATE DATABASE IF NOT EXISTS dtx_demo_mysql
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
USE dtx_demo_mysql;
```

然后在该库执行 [sql/mysql.sql](sql/mysql.sql) 全部内容。

在 PG 的 postgres 库中单独执行以下语句，不要放入事务：

```sql
CREATE DATABASE dtx_demo_pg WITH ENCODING='UTF8' TEMPLATE=template0;
```

重新连接 dtx_demo_pg，执行 [sql/postgresql.sql](sql/postgresql.sql)。库已存在时跳过建库步骤。初始化不会重置已有余额和库存。

首次初始化后，XA、OUTBOX、AT 三组独立数据各有库存 100、余额 10000.00。多次验证会消耗库存和余额，**每次以验证前的查询结果为基线**。

### 新增 TCC / Saga 的增量初始化

已有四种 Demo 可以继续使用。新增模块需先在 MySQL 执行 [sql/workflow-mysql.sql](sql/workflow-mysql.sql)
，再分别执行 [TCC MySQL](tcc-demo/sql/mysql.sql)、[TCC PostgreSQL](tcc-demo/sql/postgresql.sql)、[Saga MySQL](saga-demo/sql/mysql.sql)、[Saga PostgreSQL](saga-demo/sql/postgresql.sql)
。均为增量建表和种子初始化，不重置已有资源。完整步骤见各模块 README。

TCC 和 Saga 使用单进程跨两库的显式教学协调器，包含持久化决议、幂等分支、自动重试与人工恢复，不是 Seata TCC / Saga 引擎集成。TCC
的可用、冻结及已消费资源与 Saga 真实提交后的业务补偿可以分别观察。

## 二、IDEA 直接启动主类

1. 打开根 pom.xml，将项目 SDK、Maven Runner JRE、运行配置 JRE 都设为 JDK 17。
2. 每个 Application 的 **Working directory** 统一设置为 `D:\self\uu_distributed_transaction`。
3. 直接点击对应主类的 main 运行。各模块的 `src/main/resources/application.yml` 已配置本机连接：MySQL `3308 root/root`
   ，PostgreSQL `5432 postgres/postgres`，无需额外环境变量或专用启动配置。
4. 修改连接时编辑对应模块 YAML 的 `demo.datasource.mysql` / `demo.datasource.postgresql`。原三服务 AT
   分别运行订单、库存、账户主类，角色配置由 `application-stock.yml` / `application-account.yml` 自动加载，见模块说明。

YAML 中的 `${MYSQL_PASSWORD:root}` 等占位符允许按需使用环境变量覆盖。以下六项用于直接 JDBC 的公共集成测试，应用启动不要求填写：

```text
MYSQL_URL=jdbc:mysql://127.0.0.1:3308/dtx_demo_mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USER=root
MYSQL_PASSWORD=root
PG_URL=jdbc:postgresql://127.0.0.1:5432/dtx_demo_pg
PG_USER=postgres
PG_PASSWORD=postgres
```

数据库测试直接读取环境变量，因此运行全部测试时六项都要填写。IDEA 的环境变量表格每行填一项。应用直接读取 YAML，不依赖
config/local.ps1。

服务启动后，可用浏览器访问 `http://127.0.0.1:端口/demo/health`，应返回 `{"status":"UP"}`。

本机 YAML 直接启动的逐模块验收与 118 个测试结果见 [启动验收记录](docs/verification-yaml-startup.md)。

## 三、构建与直接启动

在根目录用 JDK 17 执行 Maven，无需运行脚本文件：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" package -DskipTests
```

也可以在 IDEA Maven 面板执行 package。只构建一个模块时要带上 common，例如：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl xa-demo -am package -DskipTests
```

各模块 README 提供直接 java -jar 命令，连接参数与 IDEA 主类启动一样读取模块 YAML。使用本机默认连接时无需设置数据库环境变量。

## 四、怎样验证

每个模块均包含三部分：

- README.md：原理、IDEA 启动配置、逐步验证、SQL 与预期结果。
- requests.http：在 IDEA HTTP Client 中逐条点击运行；也可将 URL 和 JSON 复制到 Postman / Apipost。
- src/test/java：直接在测试类或测试方法旁点击运行，自动断言并清理自己的测试数据。

每个 requests.http 顶部的 `@run` 是本轮业务号后缀。**开始新一轮时修改它；验证同一事件重试、重复消费和死信重放时保持原业务号。
** 六个方案共用全局业务号命名空间，请保留各文件的 xa/outbox/at/notify/tcc/saga 前缀。

异步方案按说明逐条执行，等待 DONE 或 DEAD 后再进入下一段，避免一键 Run All 将下一场景的故障提前注入。故障计数属于整个接收端，同一时刻只进行一组故障验证。

## 五、直接运行全部 Java 测试

先启动 [Seata TC](seata/README.md)，停止各业务 Demo 实例（TC 保持运行），尤其是 Outbox 和通知的定时任务，避免抢占测试事件及分支回调。IDEA
JUnit 配置中填写公共六项环境变量，并增加：

```text
DEMO_DB_TESTS=true
DEMO_XA_TESTS=true
DEMO_SEATA_TESTS=true
```

然后运行全项目测试，或直接执行：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" verify
```

这三个开关必须设为环境变量；仅加 Maven 的 -D 同名参数不会启用相应测试。未启用时集成测试会显示 skipped。XA 额外需要 PG
max_prepared_transactions > 0，设置方法见 XA 模块。

公共测试在 common/src/test/java 中，服务验证测试分别位于各 Demo 模块。原 seata-demo 的自动测试覆盖 Undo Log 表结构和 XID
清理，其跨服务提交/回滚通过 HTTP 场景验证；新增 seata/ 四模式具备真实框架及两库集成测试，包括提交、回滚、幂等、二阶段重试和
Saga 恢复。

## 六、运行范围

应用只监听 127.0.0.1，演示接口没有登录认证。金额使用 BigDecimal，库存和余额采用条件 UPDATE。通知方案记录已支付事实，不承担跨库原子扣款职责。

Outbox 使用数据库消息表、定时任务和 HTTP 投递；这些 Demo 当前都不依赖 RabbitMQ。可用本机 RabbitMQ 管理页面不代表本示例的事件已经通过
MQ 发送。

历史测试结果见 [docs/verification.md](docs/verification.md)。当前操作入口以本页和各模块 README 为准。

## 七、更多方案与选择依据

[完整对比与选型说明](docs/transaction-patterns.md)补充 TCC、Saga、RabbitMQ 可靠消息、RocketMQ 事务消息、CDC
Outbox、事件溯源与业务对账，区分原子提交、业务补偿、可靠投递和通知的边界。未实现的扩展均已明确标注。
本次新增方案的测试与恢复记录见 [TCC/Saga 验证记录](docs/verification-more-patterns.md)。
