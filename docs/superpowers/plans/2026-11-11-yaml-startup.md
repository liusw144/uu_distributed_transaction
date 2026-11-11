# All Demo YAML Startup Implementation Plan

**Goal:** 所有 Demo 均像已改好的 Seata XA，从各自 application.yml 读取本机连接，直接运行主类。

**Architecture:** 各模块统一 demo.datasource.mysql / postgresql 配置；公共 DatabaseFactory 先读取 YAML，再兼容旧的环境变量调用，并绑定
Hikari 参数。Atomikos 继续使用原生 XA 数据源，只复用 YAML 连接读取。原三服务 AT 增加库存/账户入口，自动加载角色 YAML，不需要专用
IDEA 配置。

**Constraints:** 保留用户修改；不新增 ps1 或启动专用 .run；MySQL3308 root/root、PG5432 postgres/postgres；Seata TC
由用户现有进程提供；不得停止用户正在运行的 8091/8102。

- [x] 公共工厂配置读取/环境兼容/Hikari 配置测试先红后绿。
- [x] 其余九模块补齐 YAML；Seata TCC 从 properties 迁至 YAML；原 AT 增加角色入口和 YAML。
- [x] 同步根及模块 README，区分直接应用启动与直接 JDBC 公共测试的环境需求。
- [x] 全量真实测试，清除数据库环境变量逐个验证其余主类、两库状态查询和原 AT 三角色（118 测试、11 主类全部通过）。
- [x] 独立审查未发现阻塞问题；本轮验证进程均已停止，保留用户 TC（8091）和 XA（8102）。
