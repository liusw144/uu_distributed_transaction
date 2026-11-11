# 所有 Demo 直接主类 / YAML 启动验收

日期：2026-11-11。工程：`D:/self/uu_distributed_transaction`；JDK 17、Spring Boot 3.3.13、Seata 2.0.0。

各模块均在 `src/main/resources/application.yml` 的 `demo.datasource.mysql` / `demo.datasource.postgresql` 配置本机连接。默认
MySQL `127.0.0.1:3308/dtx_demo_mysql root/root`，PG `127.0.0.1:5432/dtx_demo_pg postgres/postgres`。IDEA 直接运行
main，无需数据库环境变量、额外应用参数或专用 .run 配置。

## 无连接环境变量的主类启动

验收逐个启动 Java 主类，使用对应模块 `target/classes`、公共模块 classes 及 Maven runtime 依赖；不传应用参数。启动前清除
MYSQL_*、PG_*、SEATA_*、SPRING_*、DEMO_*、SERVER_PORT 及 Java 注入参数环境变量。每个服务分别完成 HTTP 状态及真实数据库读取后，仅停止本轮创建的
JVM。

| 模块                | 主类                                           | 端口   | 结果                                                     |
|-------------------|----------------------------------------------|------|--------------------------------------------------------|
| xa-demo           | cn.demo.xa.XaApplication                     | 8081 | 两库 state 200；Atomikos 原生 XA 数据源                        |
| outbox-demo       | cn.demo.outbox.OutboxApplication             | 8082 | 两库 state 200                                           |
| notification-demo | cn.demo.notification.NotificationApplication | 8086 | MySQL state 200；PG receipts 查询 200 / NOT_FOUND         |
| tcc-demo          | cn.demo.tcc.TccApplication                   | 8092 | 两库 state 200                                           |
| saga-demo         | cn.demo.saga.SagaApplication                 | 8093 | 两库 state 200                                           |
| seata-demo 库存     | cn.demo.at.SeataStockApplication             | 8084 | 自动加载 stock profile，两库连接池启动；库存接口缺 XID 返回 409，订单接口 404   |
| seata-demo 账户     | cn.demo.at.SeataAccountApplication           | 8085 | 自动加载 account profile，两库连接池启动；账户接口缺 XID 返回 409，订单接口 404 |
| seata-demo 订单     | cn.demo.at.SeataApplication                  | 8083 | 两库 state 200                                           |
| seata/at-demo     | cn.demo.seata.at.AtApplication               | 8101 | 两库 state 200                                           |
| seata/tcc-demo    | cn.demo.seata.tcc.TccApplication             | 8103 | 两库 state 200；仅 application.yml，无旧 properties 残留        |
| seata/saga-demo   | cn.demo.seata.saga.SagaApplication           | 8104 | 两库 state 200；建表 SQL 在配置绑定后执行                           |

以上 **11 个入口全部通过**。Seata XA（8102）延续上一轮已验证的 YAML / `SeataXaApplication` 实现，本轮没有修改或重启该进程。TC（8091）复用用户已运行的
2.0.0 DB 模式服务。验收未操作 IDEA UI，验证的是同一主类及编译资源的直接 Java 启动。

原三服务 AT 的数据库配置继承 `application.yml`；`application-stock.yml` / `application-account.yml`
仅覆盖角色、服务名和端口，由对应主类自动加载。

## 全量测试和构建

先设置六项数据库环境变量及 `DEMO_DB_TESTS=true`、`DEMO_XA_TESTS=true`、`DEMO_SEATA_TESTS=true`，在根目录执行
`mvn -B verify`。六项连接环境变量用于尚未加载 Spring YAML 的直接 JDBC 公共测试；应用启动不要求设置。

结果：**13 个 Maven 模块成功，118 个测试，0 失败、0 错误、0 跳过**。耗时 1 分 52 秒，完成于 16:45:10（北京时间）。Seata
框架测试与各方案提交、回滚、幂等及恢复场景均通过；生成全部应用 jar。

新增公共配置测试 5 个，覆盖 MySQL / PG 配置分离、YAML 连接与 Hikari 参数生效、显式 YAML 优先级、占位符环境覆盖、旧环境入口兼容。修改前为
3 失败 / 2 通过，修改后 5 个全部通过。

## 本机证据

- `.runtime/yaml-startup-verify.log`：完整真实数据库测试与打包日志。
- `.runtime/yaml-startup-red.log` / `yaml-startup-green.log`：公共工厂测试先失败后通过。
- `.runtime/yaml-startup-smoke.log` / `yaml-startup-results.json`：11 个主类启动及查询结果。
- `.runtime/yaml-<主类>-<端口>.log`：各服务启动日志。

`.runtime` 为忽略的本机验收产物；日常使用直接运行服务主类，并在各模块执行 `requests.http`，不需要运行验收器或 ps1。
