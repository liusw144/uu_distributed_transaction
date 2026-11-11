# 本机 Seata 源码：直接配置 MySQL DB 模式

2026-11-11 实际检查：`D:/workspace/incubator-seata` 当前 Git 分支为 `2.0.0`，`build/pom.xml` 的 revision 为 2.0.0；启动类为
`io.seata.server.ServerApplication`。已按要求直接修改源码 `server/src/main/resources/application.yml`，采用本机 MySQL 8.0
的独立 TC 库 `seata_tc_200`。

此前完整测试见 [113 个 Java 测试、61 个 HTTP 场景及实际 JVM 重启恢复](../verification.md)，当时使用本机发行包 2.2.0 的
file 模式。当前源码 DB 模式与那份历史验收区分记录；本页说明源码配置及启动方式。

## 源码中的直接配置

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

## 已初始化的 TC 数据库

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

## IDEA 直接运行（客户端和服务端均为 2.0.0）

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
USE seata_tc_200;
SELECT * FROM global_table;
SELECT * FROM branch_table;
SELECT * FROM lock_table;
SELECT * FROM distributed_lock;
```

正常结束后前三张表的对应事务记录会被清理；空表本身不表示 DB 模式未生效。可在业务全局事务执行途中暂停，再按 XID 查询。2.0.0 的
DB 模式全局记录清理可能经过恢复调度延迟；判断业务回滚还应核对实际资源、分支和锁。

本机 Demo 的订单号/资源主键可能超过官方 2.0 SQL 中 pk 的 36 字符，DB 分支注册会报 Data too long for column 'pk'。已将本机
lock_table.pk 扩至 VARCHAR(128)、row_key 扩至 VARCHAR(512)，源码 SQL 同步修改。现有库若仍是旧宽度，可只执行：

```sql
ALTER TABLE lock_table MODIFY COLUMN row_key VARCHAR(512) NOT NULL,
  MODIFY COLUMN pk VARCHAR(128) NULL;
```

## 客户端事务组

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

## 版本与端口

- 本源码和当前 Demo 均为 2.0.0，使用 io.seata 包；此前 2.2.0 结果仅为历史记录。
- 7091 / 8091 同时只运行一个 TC；源码与发行包 TC 选择一个启动。
- DB 模式的数据恢复依赖 seata_tc_200，存在未完成全局事务时不要删除协调表或切换协调数据库。此配置不会迁移旧 file 模式事务记录。

官方参考：[2.0 Server 部署](https://seata.apache.org/docs/v2.0/ops/deploy-guide-beginner/)、[2.0 配置项](https://seata.apache.org/docs/v2.0/user/configurations/)。

当前同版本联调已完成：源码构建成功，Demo 113 测试全部通过，71 个 HTTP
请求通过，服务端及客户端同时中断后从数据库恢复成功。详见 [2.0.0 + DB 验收](../verification-2.0-db.md)。临时验证服务已停止，IDEA
可直接使用上述 Application 配置启动。
