# 分布式事务 Demo：整体说明

使用 JDK 17、Spring Boot 3.3.13、MySQL 8.0、PostgreSQL、Atomikos 6.0.0 和 Seata 2.0.0，以订单、账户、库存和通知演示六类一致性方案。工程包含原有六个示例及
Seata 四种官方模式，所有服务均从各自 application.yml 读取本机连接，直接运行主类。

本页汇总公共环境、方案比较、Seata 服务端和全量验证；每个服务仅保留一份 README，集中说明本服务的原理、启动、HTTP 请求、SQL 和测试。

- [服务入口](#modules)
- [数据库准备](#数据库准备)
- [IDEA 直接启动](#idea-直接启动主类)
- [Seata Server 源码 DB 配置](#seata-server)
- [方案比较与其他扩展](#patterns)
- [验收结果](#verification)

<a id="modules"></a>

## 服务入口

### 原有六种方案

| 方案           | 验证说明                                                       | HTTP 请求                                          | 启动类 / 端口                                                                               |
|--------------|------------------------------------------------------------|--------------------------------------------------|----------------------------------------------------------------------------------------|
| XA 两阶段提交     | [xa-demo/README.md](xa-demo/README.md)                     | [requests.http](xa-demo/requests.http)           | XaApplication / 8081                                                                   |
| 本地消息表 + 定时补偿 | [outbox-demo/README.md](outbox-demo/README.md)             | [requests.http](outbox-demo/requests.http)       | OutboxApplication / 8082                                                               |
| Seata AT     | [seata-demo/README.md](seata-demo/README.md)               | [requests.http](seata-demo/requests.http)        | SeataApplication / SeataStockApplication / SeataAccountApplication（8083 / 8084 / 8085） |
| 最大努力通知       | [notification-demo/README.md](notification-demo/README.md) | [requests.http](notification-demo/requests.http) | NotificationApplication / 8086                                                         |
| TCC 资源预留     | [tcc-demo/README.md](tcc-demo/README.md)                   | [requests.http](tcc-demo/requests.http)          | TccApplication / 8092                                                                  |
| Saga 业务补偿    | [saga-demo/README.md](saga-demo/README.md)                 | [requests.http](saga-demo/requests.http)         | SagaApplication / 8093                                                                 |

三服务 AT 的库存、账户分别运行 cn.demo.at.SeataStockApplication（8084）、cn.demo.at.SeataAccountApplication（8085），再运行订单
cn.demo.at.SeataApplication（8083），角色 YAML 自动加载。

<a id="seata-modes"></a>

### Seata 四种官方模式

| 模式   | 模块与验证说明                          | HTTP 文件                                  | 启动类 / 默认端口                                 | 实际协调机制                                       |
|------|----------------------------------|------------------------------------------|--------------------------------------------|----------------------------------------------|
| AT   | [at-demo](seata/at-demo/README.md)     | [requests.http](seata/at-demo/requests.http)   | cn.demo.seata.at.AtApplication / 8101      | Seata TC + DataSourceProxy + Undo Log        |
| XA   | [xa-demo](seata/xa-demo/README.md)     | [requests.http](seata/xa-demo/requests.http)   | cn.demo.seata.xa.SeataXaApplication / 8102 | Seata TC + DataSourceProxyXA，非 Atomikos      |
| TCC  | [tcc-demo](seata/tcc-demo/README.md)   | [requests.http](seata/tcc-demo/requests.http)  | cn.demo.seata.tcc.TccApplication / 8103    | Seata TC + LocalTCC + TwoPhaseBusinessAction |
| Saga | [saga-demo](seata/saga-demo/README.md) | [requests.http](seata/saga-demo/requests.http) | cn.demo.seata.saga.SagaApplication / 8104  | Seata 官方状态机引擎 + JSON 流程 + 数据库日志              |

四个模块分别在一个 JVM 中访问 MySQL、PostgreSQL 两个真实数据库。AT、XA、TCC 需要独立 Seata TC；本项目 Saga 使用官方持久化状态机，无需
TC。跨 JVM 的 HTTP / XID 传播参考三服务 AT。根目录手写 TCC / Saga 与官方框架实现分开保留，便于对照。

本机默认 MySQL 127.0.0.1:3308，root/root；PostgreSQL 127.0.0.1:5432，postgres/postgres。RabbitMQ、Redis 当前未参与这些示例，无需为
Demo 启动。

## 数据库准备

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

### 手写 TCC / Saga 的增量初始化

手写 TCC / Saga 需先在 MySQL 执行 [sql/workflow-mysql.sql](sql/workflow-mysql.sql)
，再分别执行 [TCC MySQL](tcc-demo/sql/mysql.sql)、[TCC PostgreSQL](tcc-demo/sql/postgresql.sql)、[Saga MySQL](saga-demo/sql/mysql.sql)、[Saga PostgreSQL](saga-demo/sql/postgresql.sql)
。均为增量建表和种子初始化，不重置已有资源。完整步骤见各模块 README。

Seata AT / XA 的模块 SQL 在各自 README 中；Seata TCC / Saga 启动时自动执行资源目录的幂等 SQL，不重置已有资源。

TCC 和 Saga 使用单进程跨两库的显式教学协调器，包含持久化决议、幂等分支、自动重试与人工恢复，不是 Seata TCC / Saga 引擎集成。TCC
的可用、冻结及已消费资源与 Saga 真实提交后的业务补偿可以分别观察。

## IDEA 直接启动主类

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

## 构建与直接启动

在根目录用 JDK 17 执行 Maven，无需运行脚本文件：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" package -DskipTests
```

也可以在 IDEA Maven 面板执行 package。只构建一个模块时要带上 common，例如：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl xa-demo -am package -DskipTests
```

各模块 README 提供直接 java -jar 命令，连接参数与 IDEA 主类启动一样读取模块 YAML。使用本机默认连接时无需设置数据库环境变量。

## 怎样验证

每个模块均包含三部分：

- README.md：原理、IDEA 启动配置、逐步验证、SQL 与预期结果。
- requests.http：在 IDEA HTTP Client 中逐条点击运行；也可将 URL 和 JSON 复制到 Postman / Apipost。
- src/test/java：直接在测试类或测试方法旁点击运行，自动断言并清理自己的测试数据。

每个 requests.http 顶部的 `@run` 是本轮业务号后缀。**开始新一轮时修改它；验证同一事件重试、重复消费和死信重放时保持原业务号。
** 原有六个方案共用全局业务号命名空间，请保留各文件的 xa/outbox/at/notify/tcc/saga 前缀。

异步方案按说明逐条执行，等待 DONE 或 DEAD 后再进入下一段，避免一键 Run All 将下一场景的故障提前注入。故障计数属于整个接收端，同一时刻只进行一组故障验证。

## 直接运行全部 Java 测试

先启动 [Seata TC](#seata-server)，停止各业务 Demo 实例（TC 保持运行），尤其是 Outbox 和通知的定时任务，避免抢占测试事件及分支回调。IDEA
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

<a id="seata-server"></a>

## Seata Server：源码与 DB 模式

服务端源码位于 D:/workspace/incubator-seata，与 Demo 分属两个 Maven 工程；当前双方版本均为 2.0.0。

### 源码中的直接配置

以下内容已经写入 `D:/workspace/incubator-seata/server/src/main/resources/application.yml`。默认启动即为 DB 模式，无需启用额外
profile：

```yaml
server:
  address: 127.0.0.1
  port: 7091
seata:
  config:
    type: file
  registry:
    type: file
  server:
    service-port: 8091
  store:
    mode: db
    db:
      datasource: druid
      db-type: mysql
      driver-class-name: com.mysql.cj.jdbc.Driver
      url: jdbc:mysql://127.0.0.1:3308/seata_tc_200?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true
      user: root
      password: root
      min-conn: 2
      max-conn: 10
      global-table: global_table
      branch-table: branch_table
      lock-table: lock_table
      distributed-lock-table: distributed_lock
      query-limit: 1000
      max-wait: 5000
```

`config.type` 和 `registry.type` 仍为 file：分别表示本地配置和客户端静态寻址；`store.mode=db` 才是 TC 数据库存储，不要把前两项也改成
db。此处 root/root 是用户提供的本机演示账号。

原 `application-local.yml` 仍可用于固定本机日志路径，但已删除其中 `store.mode=file` 的覆盖，启用 local 时同样继承主配置的
DB 模式。控制台账号沿用 seata/seata。

### 已初始化的 TC 数据库

已在 MySQL 8.0.46（127.0.0.1:3308）创建 `seata_tc_200`，并首次执行本份源码的 `script/server/db/mysql.sql`。当前包含：

| 表                | 用途                    |
|------------------|-----------------------|
| global_table     | 全局事务状态                |
| branch_table     | 分支事务状态                |
| lock_table       | AT 全局锁                |
| distributed_lock | TC 恢复任务的分布式锁，已初始化四条记录 |

TC 库与 `dtx_demo_mysql` / `dtx_demo_pg` 两个业务库分开。AT 的 undo_log 仍在各业务库，Saga 引擎表仍在业务演示库。源码 SQL
的四条 distributed_lock INSERT 用于首次初始化；已有库无需重复插入或清空表。

源码 `server/pom.xml` 原依赖旧版驱动，现已显式使用 `mysql-connector-java:8.0.27`，匹配 `com.mysql.cj.jdbc.Driver`。IDEA
修改后需要 Reload Maven。

### IDEA 直接运行（客户端和服务端均为 2.0.0）

本地 Demo 已统一为 `io.seata:seata-spring-boot-starter:2.0.0`，API 包名也同步为 `io.seata.*`。五个 Seata 客户端模块均继承根
pom.xml 的 seata.version=2.0.0。

源码已经提供 `.run/Seata Server 2.0 DB.run.xml`。IDEA 导入源码根 pom.xml 并 Reload Maven 后，直接选择 **Seata Server 2.0
DB** 点击 Run。配置内容为：

| 设置                      | 本机值                                           |
|-------------------------|-----------------------------------------------|
| Main class              | io.seata.server.ServerApplication             |
| Use classpath of module | server / seata-server                         |
| Working directory       | D:/workspace/incubator-seata                  |
| JRE                     | JDK 17（源码编译级别保持其 POM 的 1.8）                   |
| VM options              | 可留空；需要本机日志路径时加 -Dspring.profiles.active=local |
| Environment variables   | SEATA_IP=127.0.0.1;SEATA_PORT=8091            |
| Program arguments       | 留空                                            |

源码工程 Project SDK 已调整为本机已注册的 JDK 17，Maven 编译级别保持 1.8，运行配置明确使用 JDK 17，并使用参数文件缩短
Windows classpath。两个工程都新增本机 .mvn/maven.config，指向 HTTPS Maven settings 和已下载的 .runtime/m2；IDEA Maven
设置中应启用读取 .mvn/maven.config。

若使用 local profile，请放在 VM options：该源码的 ServerRunner 会把 Program arguments 再交给 JCommander；将 Spring 配置参数直接填到
Program arguments 可能被它判为未知参数。源码的编译级别保持 POM 原值，两者是不同 Maven 工程。

Run 后日志应显示 session / lock store mode 为 db，以及 `service listen port: 8091`。访问 http://127.0.0.1:7091/ 登录；Demo
客户端连接 **127.0.0.1:8091**。7091 为控制台，不能写入客户端 grouplist。

也可从源码根目录直接构建，PowerShell 中 -D 参数保留引号：

```text
mvn -B -s D:/self/uu_distributed_transaction/scripts/maven-settings.xml "-Dmaven.repo.local=D:/self/uu_distributed_transaction/.runtime/m2" -pl server -am -Prelease-seata "-Dmaven.test.skip=true" "-Dimage.publish.skip=true" package
```

上面只用于提前准备依赖和编译；日常从 IDEA 运行主类，无需运行发行包 JAR。构建使用 Java 17，跳过的是 Seata 上游源码自身的完整测试。
`image.publish.skip=true` 避免构建容器镜像。命令不调用 ps1。

MySQL 可观察协调记录：

```sql
USE
seata_tc_200;
SELECT *
FROM global_table;
SELECT *
FROM branch_table;
SELECT *
FROM lock_table;
SELECT *
FROM distributed_lock;
```

正常结束后前三张表的对应事务记录会被清理；空表本身不表示 DB 模式未生效。可在业务全局事务执行途中暂停，再按 XID 查询。2.0.0 的
DB 模式全局记录清理可能经过恢复调度延迟；判断业务回滚还应核对实际资源、分支和锁。

本机 Demo 的订单号/资源主键可能超过官方 2.0 SQL 中 pk 的 36 字符，DB 分支注册会报 Data too long for column 'pk'。已将本机
lock_table.pk 扩至 VARCHAR(128)、row_key 扩至 VARCHAR(512)，源码 SQL 同步修改。现有库若仍是旧宽度，可只执行：

```sql
ALTER TABLE lock_table MODIFY COLUMN row_key VARCHAR (512) NOT NULL,
    MODIFY COLUMN pk VARCHAR (128) NULL;
```

### 客户端事务组

本项目 AT / XA 使用以下配置，TCC 的事务组名为 seata_tcc_group，同样映射到 default：

```yaml
seata:
  tx-service-group: demo_tx_group
  registry:
    type: file
  config:
    type: file
  service:
    vgroup-mapping:
      demo_tx_group: default
    grouplist:
      default: 127.0.0.1:8091
```

file 注册模式下 TC 不需要逐项填写客户端的业务事务组名称。transaction group → default → TC 地址的映射在客户端。AT / XA /
TCC 需要运行 TC，本项目 Saga 官方状态机独立使用数据库日志，不要求启动 TC。

### 恢复与端口约束

- 本源码和当前 Demo 均为 2.0.0，使用 io.seata 包；客户端依赖使用 io.seata:seata-spring-boot-starter:2.0.0。
- 7091 / 8091 同时只运行一个 TC；源码与发行包 TC 选择一个启动。
- DB 模式的数据恢复依赖 seata_tc_200，存在未完成全局事务时不要删除协调表或切换协调数据库。此配置不会迁移旧 file 模式事务记录。

官方参考：[2.0 Server 部署](https://seata.apache.org/docs/v2.0/ops/deploy-guide-beginner/)、[2.0 配置项](https://seata.apache.org/docs/v2.0/user/configurations/)。

<a id="patterns"></a>

## 方案比较与其他扩展

先区分三类问题：跨库操作是否必须原子提交，业务数据与事件是否可靠地一起落库，外部通知是否收到有效确认。这些机制可以组合，不能仅按技术名称数量或性能高低排序。

### 六种可运行演示

| 方式                                      | 机制                          | 一致性与可见性                 | 主要代价                       | 场景                |
|-----------------------------------------|-----------------------------|-------------------------|----------------------------|-------------------|
| [XA](xa-demo/README.md)                 | prepare / commit / rollback | 跨资源原子提交；读隔离仍受数据库与访问方式约束 | 预备事务占用资源，协调器故障恢复           | 支持 XA 的跨库原子操作     |
| [TCC](tcc-demo/README.md)               | Try 预留，Confirm 消耗，Cancel 释放 | 业务冻结可见，参与者必须共同遵守资源约束    | 接口改造、幂等、防悬挂和冻结占用           | 库存预占、余额冻结、额度扣减    |
| [Saga](saga-demo/README.md)             | 每步本地提交，失败后反向业务补偿            | 中间态可见，通常不提供跨步骤隔离        | 可补偿业务设计、处理补偿失败与竞争          | 长流程、跨组织操作、履约      |
| [Outbox + Inbox](outbox-demo/README.md) | 业务和事件同事务，可靠投递，消费者去重         | 异步最终一致                  | 重试、积压、顺序、死信与对账             | 跨服务业务状态同步         |
| [Seata AT](seata-demo/README.md)        | 本地业务及 Undo Log，全局锁和二阶段补偿    | 普通读可能看到中间态；写隔离依赖全局锁协议   | SQL / 数据源支持范围、脏写和 Undo Log | 受控 Java / 关系数据库服务 |
| [最大努力通知](notification-demo/README.md)   | 有界重试、业务 ACK 和回执幂等           | 超过重试上限仍可能没有送达           | 需要对账与人工处理，不能替代原子业务         | 支付回调、短信、第三方通知     |

上表根目录的 TCC 与 Saga 使用单进程跨两个真实数据库的显式教学协调器，不是 Seata TCC / Saga
框架集成。具备持久化决议、分支幂等、自动重试和人工恢复；生产还需独立服务通信、认证、监控和更完整的故障演练。

另有独立的 [Seata 四种官方模式](#seata-modes)：AT 使用 DataSourceProxy，XA 使用 DataSourceProxyXA（由 Seata TC
协调），TCC 使用官方 TCC 注解和 TC 回调，Saga 使用官方状态机引擎和数据库日志。它们与原有示例分目录保留，可以比较同一模式的不同实现。

### TCC 与 Saga 的关键区别

同样是购买两件商品、支付 10 元：

- TCC 的 Try 减少可用量、增加冻结量；Confirm 将冻结转成已消费，Cancel 释放冻结。已确认的资源不能再把 Cancel 当作退款接口。
- Saga 的正向动作已真实扣库存和余额，后续失败通过新的业务事务退款、补库存。原始提交发生过，通知、审计、优惠等副作用需要另行设计。
- 两者都不能只在 Java catch 中循环恢复资源。必须知道已持久化的决议、分支是否执行过，并保证重试不重复扣减或补回。
- Saga 中发货后的撤销可能对应拦截、退货、退款等业务补偿，无法补偿的步骤需要调整流程。

参考：[Seata TCC](https://seata.apache.org/docs/user/mode/tcc/)、[幂等、空回滚与防悬挂](https://seata.apache.org/blog/seata-tcc-fence/)、[Saga](https://seata.apache.org/docs/user/mode/saga/)。

### 其他方案及其关系

#### RabbitMQ 可靠消息：与 Outbox 组合

当前 Outbox 的 HTTP 投递可以扩展为 RabbitMQ：

1. 生产者在本地事务中提交业务数据和 Outbox。
2. 投递器发布持久化消息，使用 publisher confirms 并处理不可路由等异常，确认后标记投递结果。
3. 消费者在一个本地事务内写 Inbox 幂等键并更新业务数据，提交完成后再 ACK。
4. 发布确认或消费 ACK 丢失可能造成重复，重试必须使用原事件编号；死信与长期未知状态需要告警和对账。

Publisher confirm 确认的是 Broker 对发布的处理，不证明消费者的业务事务已完成；消费 ACK
与发布确认是不同链路。[RabbitMQ 确认机制](https://www.rabbitmq.com/docs/confirms)、[可靠性指南](https://www.rabbitmq.com/docs/reliability)。

这是已有 Outbox 的传输扩展，本次未把现有 Demo 自动替换成 RabbitMQ，也不把 MQ 本身算作跨库原子提交协议。

#### 事务消息：以 RocketMQ 为例

事务消息将生产者本地事务结果与消息可见性关联。提交结果未知时，由回查机制查询本地事务状态，决定消息提交或回滚；事务结果必须可持久查询，不能只放在内存。

它解决生产端的一致性，消费端仍须本地事务、幂等及失败处理。本机 RabbitMQ 的 publisher confirm
不等同于这一协议。[RocketMQ 事务消息](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)。

本工程未安装或集成 RocketMQ，此项为选型说明。

#### CDC Outbox：用数据库日志替代轮询

业务仍在本地事务中写 Outbox，Debezium 等组件读取数据库日志并路由事件。可以减少轮询扫描，但增加 Connector、日志保留、offset
恢复与 schema
演进等运维要求；消费者仍要处理重复和顺序。[Debezium Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)。

它是事件采集方式的替换，不会自动把两个业务数据库合成一个 ACID 事务。本工程未部署 CDC 组件。

#### 事件溯源、对账和本地事务边界

事件溯源记录业务事实演变，跨聚合、跨服务一致性仍需要明确事务边界，通常与 Saga / Outbox
组合。业务对账可发现并修复漏单、未知状态和重复结果；修复本身也要幂等、状态校验及审计。

如果可以调整业务或服务边界，把必须原子变化的数据放进一个数据库事务，往往可以消除一部分分布式事务需求。

#### 协议、API 和框架名称不能混为一谈

2PC 是协议类别，XA 是常见资源接口及协调方式，本工程使用 Atomikos 实现。3PC 等变体属于协议层讨论，这里不提供实现，也不能只凭阶段数量判断其失效模型。

JTA 是 Java 事务 API；Atomikos / Narayana 是事务管理器；Seata 是多模式框架。不应将这些名称都算成独立的新业务一致性模型。

### 选择建议

| 业务约束              | 优先考虑          | 必须先回答                                  |
|-------------------|---------------|----------------------------------------|
| 单库能完成原子操作         | 本地事务          | 是否有必要拆成远程步骤？                           |
| XA 参与资源要求原子提交     | XA            | 所有资源是否支持 XA？prepared transaction 如何恢复？ |
| 业务允许冻结且能改造        | TCC           | 空取消、预留过期、Confirm 失败如何恢复？               |
| 长流程且步骤可补偿         | Saga          | 哪些动作不可逆？如何处理中间态及补偿失败？                  |
| 允许异步推进跨服务状态       | Outbox / 事务消息 | 本地提交如何关联发布？幂等、顺序、死信怎样设计？               |
| SQL 场景受控，减少业务补偿代码 | Seata AT      | 数据源代理、SQL 支持及全局锁协议是否适合？                |
| 外部回调通知            | 最大努力通知 + 对账   | 对方如何幂等和查询结果？重试耗尽由谁处理？                  |

不存在通用的性能排名。应结合延迟、故障持续时间、资源占用、隔离要求、参与者能力和恢复成本进行验证。

<a id="verification"></a>

## 验收结果与运行边界

最近一次业务代码验收（2026-11-11）：**13 个 Maven 模块成功，118 个测试通过，0 失败、0 错误、0 跳过**。其中原有六方案及公共基础
82 个测试，Seata AT 8、XA 8、TCC 9、Saga 11。

| 验证范围               | 已有验收结果                                                                             |
|--------------------|------------------------------------------------------------------------------------|
| YAML 主类启动          | 清除数据库等覆盖环境变量、不传应用参数，其余 11 个入口均成功启动并读取两库；Seata XA 沿用此前已通过的直接启动验证                    |
| Seata 2.0.0 HTTP   | 四模式 AT 12、XA 12、TCC 17、Saga 20，共 61 个场景通过；三服务 AT 另有 10 个请求通过                       |
| 手写 TCC / Saga HTTP | 分别 20 / 25 个场景通过，包括确认、取消、丢 ACK、补偿及人工恢复                                             |
| TC DB 存储           | AT 事务途中观察到 1 条全局事务、2 条分支、3 条锁；全局回滚后两库恢复，协调记录清理                                     |
| 实际进程重启恢复           | TC 与 Seata TCC 客户端中断后，从 DB 继续 Confirm；Saga 重启后从失败补偿实例恢复且不重复退款；手写协调器也已验证实际 JVM 重启恢复 |

这些结果为上述验收时点的记录，不代表服务持续运行。本次仅整理文档，未重新执行业务集成测试。本机证据在忽略目录
.runtime/：yaml-startup-verify.log、yaml-startup-smoke.log、seata-200-http.log、seata-200-legacy-http.log、seata-200-restart.log、more-patterns-http.log、more-patterns-restart.log；JUnit
报告在各模块 target/surefire-reports。

应用只监听 127.0.0.1，演示接口没有登录认证。金额使用 BigDecimal，库存、余额采用条件 UPDATE。两库 state
查询是观察快照，不提供跨库原子读，异步回滚时等待 TC 重试和最终分支状态。成功验证会消耗库存与余额，每轮重新读取基线。

最大努力通知记录已支付事实，不承担跨库原子扣款。Outbox 当前通过定时任务和 HTTP 投递；RabbitMQ 管理页面可用不代表事件已通过
MQ 发送。

未验证生产高可用、吞吐、数据库断电及所有网络分区时序。Saga 恢复接口仅支持已停止的失败补偿，任意正向位置中断需要另行诊断。各服务的更具体边界、恢复步骤和验证
SQL 保留在对应 README。
