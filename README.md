# 分布式事务一致性：四种方案 Demo

使用 JDK 17、Spring Boot 3.3.13、MySQL 8.0、PostgreSQL、Atomikos 6.0.0 和 Seata 2.2.0。验证说明、HTTP 请求和 Java 测试均放在对应服务模块中，可直接在 IDEA 运行。

| 方案 | 验证说明 | HTTP 请求 | 启动类 / 端口 |
|---|---|---|---|
| XA 两阶段提交 | [xa-demo/README.md](xa-demo/README.md) | [requests.http](xa-demo/requests.http) | XaApplication / 8081 |
| 本地消息表 + 定时补偿 | [outbox-demo/README.md](outbox-demo/README.md) | [requests.http](outbox-demo/requests.http) | OutboxApplication / 8082 |
| Seata AT | [seata-demo/README.md](seata-demo/README.md) | [requests.http](seata-demo/requests.http) | SeataApplication / 8083、8084、8085 |
| 最大努力通知 | [notification-demo/README.md](notification-demo/README.md) | [requests.http](notification-demo/requests.http) | NotificationApplication / 8086 |

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

## 二、IDEA 公共运行配置

1. 打开根 pom.xml，将项目 SDK、Maven Runner JRE、运行配置 JRE 都设为 JDK 17。
2. 每个 Application 的 **Working directory** 统一设置为 `D:\self\uu_distributed_transaction`。
3. 在 **Environment variables** 中分别配置以下六项；密码使用本机实际值，不提交到 Git：

```text
MYSQL_URL=jdbc:mysql://127.0.0.1:3308/dtx_demo_mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USER=root
MYSQL_PASSWORD=本机MySQL密码
PG_URL=jdbc:postgresql://127.0.0.1:5432/dtx_demo_pg
PG_USER=postgres
PG_PASSWORD=本机PostgreSQL密码
```

应用可以使用默认 URL，但数据库测试直接读取环境变量，因此测试配置中六项都要填写。IDEA 的环境变量表格每行填一项。Java 应用不会自动加载 config/local.ps1。

服务启动后，可用浏览器访问 `http://127.0.0.1:端口/demo/health`，应返回 `{"status":"UP"}`。

## 三、构建与直接启动

在根目录用 JDK 17 执行 Maven，无需运行脚本文件：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" package -DskipTests
```

也可以在 IDEA Maven 面板执行 package。只构建一个模块时要带上 common，例如：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" -pl xa-demo -am package -DskipTests
```

各模块 README 提供直接 java -jar 命令。命令执行前须在当前终端设置上述六项环境变量；IDEA 运行配置中的变量不会自动传入外部终端。Windows CMD 可以逐项执行 `set "变量名=变量值"`，Linux/macOS 使用 export。

## 四、怎样验证

每个模块均包含三部分：

- README.md：原理、IDEA 启动配置、逐步验证、SQL 与预期结果。
- requests.http：在 IDEA HTTP Client 中逐条点击运行；也可将 URL 和 JSON 复制到 Postman / Apipost。
- src/test/java：直接在测试类或测试方法旁点击运行，自动断言并清理自己的测试数据。

每个 requests.http 顶部的 `@run` 是本轮业务号后缀。**开始新一轮时修改它；验证同一事件重试、重复消费和死信重放时保持原业务号。** 四个方案共用全局业务号命名空间，请保留各文件的 xa/outbox/at/notify 前缀。

异步方案按说明逐条执行，等待 DONE 或 DEAD 后再进入下一段，避免一键 Run All 将下一场景的故障提前注入。故障计数属于整个接收端，同一时刻只进行一组故障验证。

## 五、直接运行全部 Java 测试

先停止本项目的运行实例，尤其是 Outbox 和通知的定时任务，避免抢占测试事件。IDEA JUnit 配置中填写公共六项环境变量，并增加：

```text
DEMO_DB_TESTS=true
DEMO_XA_TESTS=true
```

然后运行全项目测试，或直接执行：

```text
mvn -B -s scripts/maven-settings.xml "-Dmaven.repo.local=.runtime/m2" verify
```

这两个开关必须设为环境变量；仅加 Maven 的 -DDEMO_DB_TESTS=true 不会启用使用 System.getenv 的测试。未启用时数据库测试会显示 skipped。XA 额外需要 PG max_prepared_transactions > 0，设置方法见 XA 模块。

公共测试在 common/src/test/java 中，服务验证测试分别位于四个 Demo 模块。Seata 的自动测试覆盖 Undo Log 表结构和 XID 清理，真正跨服务的全局提交/回滚通过 Seata 模块的 HTTP 场景验证。

## 六、运行范围

应用只监听 127.0.0.1，演示接口没有登录认证。金额使用 BigDecimal，库存和余额采用条件 UPDATE。通知方案记录已支付事实，不承担跨库原子扣款职责。

Outbox 使用数据库消息表、定时任务和 HTTP 投递；这四个 Demo 当前都不依赖 RabbitMQ。可用本机 RabbitMQ 管理页面不代表本示例的事件已经通过 MQ 发送。

历史测试结果见 [docs/verification.md](docs/verification.md)。当前操作入口以本页和四个模块 README 为准。
