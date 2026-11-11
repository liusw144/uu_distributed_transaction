# Seata 四种模式 Demo

用户要求：补全 Seata 的所有模式，集中放在新建 seata 目录；使用本机数据库，中文注释，每个模块直接提供验证步骤，无 ps1 依赖。

## 实现决定

- 新增 `seata/{at-demo,xa-demo,tcc-demo,saga-demo}`，保留原有六个示例及未提交更改。
- Spring Boot 3.3.13、Java 17、Apache Seata 2.2.0；复用本机 Seata TC。MySQL 3308、PostgreSQL 5432，沿用专用演示库。
- 每个模块一个 JVM、两个真实数据库。数据库分布式事务与跨 JVM RPC 的边界在 README 说明；旧 AT 三服务示例仍可对比。
- AT 使用 DataSourceProxy、Undo Log 和 TC；XA 使用 DataSourceProxyXA 和 TC，不能使用 Atomikos 代替。
- TCC 使用 @LocalTCC、@TwoPhaseBusinessAction、@GlobalTransactional 和真实 TC，业务分支持久化，保证幂等、空回滚和防悬挂。
- Saga 使用 Seata 官方状态机引擎、流程 JSON、数据库日志、业务幂等补偿及恢复入口，不能用旧手写协调器代替。
- 新场景资源及动作表使用独立前缀，不重置旧库存余额。端口 8101 / 8102 / 8103 / 8104。
- 不需要 RabbitMQ、Redis、Nacos、Docker；使用 file 注册发现，TC 127.0.0.1:8091。Saga 不依赖 TC 的 XA/AT 分支协调。

## 方案取舍

可将旧示例迁移，也可新增真正接入 Seata 的模块。采用后者，因为用户指定新目录，且保留手写实现和 Atomikos
有助于比较协议与框架。单进程两库降低启动成本，独立 RPC 不属于本轮必要条件。

## 验收

每个模块具备 README、requests.http、幂等初始化 SQL、源码中文注释、真实框架集成测试。验证成功扣减、不同阶段异常、资源不足、幂等和补偿重试；验证
Saga 引擎持久化日志与恢复。运行全工程测试，记录实际结果，清理仅本轮启动的进程。未经新要求不提交或推送。

## 后续明确要求：统一 2.0.0、源码 IDEA 启动及 DB 存储

用户后续要求覆盖原版本/部署决定：客户端依赖和 API 统一为 io.seata 2.0.0；D:/workspace/incubator-seata 2.0.0 源码在 IDEA
中直接运行 ServerApplication；协调状态使用 MySQL3308 的 seata_tc_200。当前 .run 为 Application 配置，原 2.2.0/file
发行包仅保留历史。新版本重新完成全部验证，结果见 seata/verification-2.0-db.md。
