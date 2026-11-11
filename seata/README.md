# Seata 四种事务模式

本目录集中提供 Apache Seata 2.0.0 的 AT、XA、TCC、Saga 四种真实框架集成；原有 Atomikos XA、手写 TCC / Saga 和三服务 AT
均保留在根目录对应模块中。

| 模式   | 模块与验证说明                          | HTTP 文件                                  | 启动类 / 默认端口                                 | 实际协调机制                                       |
|------|----------------------------------|------------------------------------------|--------------------------------------------|----------------------------------------------|
| AT   | [at-demo](at-demo/README.md)     | [requests.http](at-demo/requests.http)   | cn.demo.seata.at.AtApplication / 8101      | Seata TC + DataSourceProxy + Undo Log        |
| XA   | [xa-demo](xa-demo/README.md)     | [requests.http](xa-demo/requests.http)   | cn.demo.seata.xa.SeataXaApplication / 8102 | Seata TC + DataSourceProxyXA，非 Atomikos      |
| TCC  | [tcc-demo](tcc-demo/README.md)   | [requests.http](tcc-demo/requests.http)  | cn.demo.seata.tcc.TccApplication / 8103    | Seata TC + LocalTCC + TwoPhaseBusinessAction |
| Saga | [saga-demo](saga-demo/README.md) | [requests.http](saga-demo/requests.http) | cn.demo.seata.saga.SagaApplication / 8104  | Seata 官方状态机引擎 + JSON 流程 + 数据库日志              |

每个应用在一个 JVM 中操作 MySQL 与 PostgreSQL 两个真实数据库，便于单独启动学习；没有用跨库本地事务冒充分布式事务。AT / XA /
TCC 的协调器是独立 Seata Server，Saga 由官方状态机引擎编排与恢复。跨 JVM HTTP / RPC
的传播可以参考原 [三服务 AT 示例](../seata-demo/README.md)。

## 本机依赖

- JDK 17、Maven；本项目 Spring Boot 3.3.13、Seata 2.0.0。
- MySQL 8.0：127.0.0.1:3308，root/root，专用库 dtx_demo_mysql。
- PostgreSQL：127.0.0.1:5432，postgres/postgres，专用库 dtx_demo_pg。
- Seata Server 2.0.0：源码 D:/workspace/incubator-seata，通过 IDEA 主类启动；TC 8091，控制台 7091，协调数据存入 MySQL 的
  seata_tc_200。
- 本轮无需新增组件。RabbitMQ、Redis 未参与这四个演示，注册发现使用 file 配置。

先按各模块 README 初始化数据库：AT / XA 手动执行 sql/ 下的 SQL；TCC / Saga 启动时执行模块资源目录中的幂等
SQL，也可以手动执行。均不重置已有演示数据。AT / XA 使用 SEATA_AT / SEATA_XA 资源前缀，TCC / Saga 使用独立 seata_tcc_* /
seata_saga_* 表。

## IDEA 直接启动 Seata TC

IDEA 打开 D:/workspace/incubator-seata/pom.xml 并 Reload Maven，选择源码工程内的 **Seata Server 2.0 DB** 运行配置。配置文件位于源码的
.run/Seata Server 2.0 DB.run.xml，启动类 io.seata.server.ServerApplication，模块 seata-server，JDK 17，工作目录为源码根目录。

源码 application.yml 已直接配置 store.mode=db、MySQL 127.0.0.1:3308/seata_tc_200。local profile 只设置日志及本机地址，不覆盖
DB 模式。客户端依赖统一为 io.seata:seata-spring-boot-starter:2.0.0，Java API 使用 io.seata 包。

控制台为 http://127.0.0.1:7091/，演示账号 seata/seata；客户端连接 127.0.0.1:8091。日志显示 session / lock store mode 为
db，service listen port: 8091 后再运行 AT / XA / TCC。

完整源码配置、TC 四张表及 IDEA 设置见 [server/README.md](server/README.md)。旧 .runtime/seata 中的 2.2.0 发行包和
config/seata-server.yml 属于历史 file 模式环境，不再作为当前 2.0.0 Demo 的默认启动方式。

## IDEA 或直接 Java 启动模块

IDEA 导入根 pom.xml，项目 SDK 设置 JDK 17，按各模块 README 运行启动类，工作目录为项目根目录，并填写以下环境变量：

```text
MYSQL_URL=jdbc:mysql://127.0.0.1:3308/dtx_demo_mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USER=root
MYSQL_PASSWORD=root
PG_URL=jdbc:postgresql://127.0.0.1:5432/dtx_demo_pg
PG_USER=postgres
PG_PASSWORD=postgres
```

终端可直接设置这些环境变量，无需创建任何脚本。若 PATH 默认是 Java 8，先设置 JAVA_HOME 为本机 JDK 17 路径，并将其 bin 加入
PATH。

```text
mvn -B -s scripts/maven-settings.xml -Dmaven.repo.local=.runtime/m2 -pl seata/at-demo,seata/xa-demo,seata/tcc-demo,seata/saga-demo -am -DskipTests package
java -jar seata/at-demo/target/seata-at-demo-1.0.0.jar
java -jar seata/xa-demo/target/seata-xa-demo-1.0.0.jar
java -jar seata/tcc-demo/target/seata-tcc-demo-1.0.0.jar
java -jar seata/saga-demo/target/seata-saga-demo-1.0.0.jar
```

四条 java 命令分别在独立终端运行，按需选择模块。具体验证、预期 SQL 结果和恢复步骤均在对应模块 README、requests.http 与 JUnit
测试中。

## 框架测试

先启动 TC、MySQL、PostgreSQL，设置以上数据库环境变量和 DEMO_SEATA_TESTS=true：

```text
mvn -B -s scripts/maven-settings.xml -Dmaven.repo.local=.runtime/m2 -pl seata/at-demo,seata/xa-demo,seata/tcc-demo,seata/saga-demo -am verify
```

开启原有测试时另设 DEMO_DB_TESTS=true、DEMO_XA_TESTS=true，再从根目录执行 mvn verify。未设置测试环境变量会跳过相应集成测试；Maven
的 -D 同名参数不能代替环境变量。

模式定义参考官方文档：[AT](https://seata.apache.org/docs/v2.0/user/mode/at/)、[XA](https://seata.apache.org/docs/v2.0/user/mode/xa/)、[TCC](https://seata.apache.org/docs/v2.0/user/mode/tcc/)、[Saga](https://seata.apache.org/docs/v2.0/user/mode/saga/)。

当前 2.0.0 + DB 模式的完整测试、HTTP 验证与服务端 / 客户端重启恢复证据见 [verification-2.0-db.md](verification-2.0-db.md)
。此前 [2.2.0 + file 验收](verification.md) 保留作历史记录。

本机源码 D:/workspace/incubator-seata 的版本检查、application-local.yml 和 IDEA Server
启动设置见 [server/README.md](server/README.md)。
