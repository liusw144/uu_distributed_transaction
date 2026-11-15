# integrated-storage：库存分支

维护商品库存，是订单全局事务中的 Seata AT 分支。学习重点是本地事务、带库存条件的扣减，以及 Order 通过 OpenFeign 传播 XID
后发生余额不足时，之前扣除的库存如何被全局回滚。Order 通过 Nacos 与 LoadBalancer 查找本服务；Gateway 负责外部入口。

来源为 Spring Cloud Alibaba 官方 `2023.0.3.4` 的 integrated-example 场景；本仓库使用 Java
17，统一依赖版本和完整操作入口见[仓库根 README](../../README.md)。复制来源遵循子树内的 [Apache-2.0 LICENSE](../LICENSE)
与 [NOTICE](../NOTICE)。

## 启动与配置

- 在 IDE 运行 `com.alibaba.cloud.integration.storage.StorageServiceApplication`，端口 `18082`，Nacos 服务名
  `integrated-storage`。
- 配置：[src/main/resources/application.yml](src/main/resources/application.yml)。默认使用本机 YAML，直接运行 main，无需修改
  hosts 或发布 Nacos 配置。
- 前置依赖：MySQL `127.0.0.1:3308`，用户名/密码 `root/root`；Nacos `127.0.0.1:8848`，发现组 `UU_INTEGRATED_EXAMPLE`；Seata TC
  `127.0.0.1:8091`，事务组 `uu_integrated_tx_group`。
- 先执行[统一初始化脚本](../sql/mysql.sql)，本服务使用 `sca_integrated_storage`。下单实验还需 Account `18083` 与 Order
  `18081`；页面实验再启动 Gateway `18085`、Frontend `18080`。订单链路不需要 RocketMQ。

## 请求与预期

[requests.http](requests.http) 先读取余额/库存基线，验证直接无 XID 扣减被拒绝，再经 Order 下单验证提交和回滚。`code=2001`
是业务成功，`code=2003` 是业务失败，不能仅依据 HTTP 状态判断结果。

```http
GET http://127.0.0.1:18082/storage/?commodityCode=1

POST http://127.0.0.1:18082/storage/reduce-stock
Content-Type: application/json

{"commodityCode":"1","count":1}
```

查询返回 `data` 中的库存。上面的直接扣库存未携带真实全局事务 XID，预期业务失败且库存不变；不要伪造 XID。正常调用由 Order 的
Feign 客户端传入同样的 JSON 和真实 XID。

```sql
SELECT commodity_code, `count` FROM sca_integrated_storage.storage WHERE commodity_code = '1';
SELECT COUNT(*) AS order_count FROM sca_integrated_order.`order` WHERE user_id = 'admin';
```

**下单会消耗共用演示数据；请逐条执行，不要连续运行多份 requests.http。** 新库的 `admin` 余额为 `3`、商品 `1` 库存为 `100`，单价
`2`，金额使用整数教学单位。第一次购买 `1` 件后库存 `99`、余额 `1`、订单数增加 `1`。第二次购买先触发库存分支，但因余额不足导致全局回滚；结束后库存仍为
`99`、余额仍为 `1`，订单数不再增加。结合两个分支与 Order 日志中的同一个 XID 理解事务边界。

初始化 SQL 幂等，不会恢复已消耗库存和余额。非 `3/100` 基线请先停止固定预期的演示、查询实际数据并参考根 README，不要直接重置其他数据。

## 已验证范围

2026-11-15：6 个单元测试通过，覆盖非法数量、空商品、缺少 XID、商品不存在、条件更新失败与无 XID 查询。
真实三服务联调验证了 Feign XID 传播、成功扣库存、后续扣款失败后的库存恢复，以及无 XID 扣减和库存不足被拒绝。
详情见[订单验收](../integrated-order/README.md#已验证范围)。
