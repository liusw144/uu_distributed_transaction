# 更多分布式事务方案实现计划

> 执行方式：公共协调器由主代理实现，按 dispatching-parallel-agents 技能将两个互不修改对方文件的业务分支分开实施；完成后统一测试与独立审查。

**Goal:** 补全 TCC 和 Saga 可运行示例及方案选型。
**Architecture:** common 持久化协调器 + 两个独立业务模块，共用 MySQL / PG，分支各自本地事务。
**Tech Stack:** 沿用 JDK 17 / Spring Boot 3.3.13 / JDBC / 两个本地数据库。
**Spec:** docs/superpowers/specs/2026-11-11-more-patterns-design.md

## Global Constraints

中文关键注释；不依赖 ps1；不删除旧文件；新测试数据使用随机编号并清理；不提交本地密码。8092 / 8093 不占用现有 8088 服务。

## Review Focus

- 二阶段 ACK 丢失不会重复 Confirm、退款、释放库存。
- Cancel/补偿先到时，迟到正向动作必须被墓碑拦截。
- 决议落库后不可由 Commit 切换到 Cancel。
- 重试耗尽保留待恢复决议，不把未知结果伪装终态。
- 协调状态和订单创建/完成应在同一本地事务，重复业务号内容冲突拒绝。

## Tasks

- [x] 
    1. common 接口、协调表与测试先行，完成持久化状态机、重试、到期取消和 REST 查询。
- [x] 
    2. TCC 模块：分支 SQL、Try/Confirm/Cancel、真实 DB 用例、模块文档与 HTTP。
- [x] 
    3. Saga 模块：正向/补偿 SQL、幂等防悬挂、真实 DB 用例、模块文档与 HTTP。
- [x] 
    4. 根目录集成、方案对比，直接 Maven 全量验证和真实 HTTP 场景验证。
- [x] 
    5. 独立审查并修复重要问题，保留验证记录。

## 接口衔接

所有模块只依赖 common 中 ProtocolParticipant。模块提供该接口唯一 Bean；common 配置通过 demo.workflow.enabled=true 启用。
公共端点 /api/${demo.workflow.protocol}：POST /orders?prepareOnly=false；GET /orders/{id}；GET /state；POST
/orders/{id}/confirm、/cancel、/retry；POST /faults?phase=confirm|cancel|compensate&failNext=0&loseAckNext=0。
phase 为小写，资源执行顺序正向 STOCK->ACCOUNT，补偿 ACCOUNT->STOCK，Confirm STOCK->ACCOUNT。

完成记录：原有15 + 协调器13 + TCC23 + Saga26 =
77项测试全部通过；45个模块HTTP场景通过；TCC确认与Saga补偿各做一次真实进程中断恢复。独立审查发现的决议覆盖、截止时间及大小写等价锁键已用失败回归用例修复。容量限制保留为教学范围并记录在
docs/verification-more-patterns.md。
