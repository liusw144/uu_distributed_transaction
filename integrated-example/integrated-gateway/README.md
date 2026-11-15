# integrated-gateway：HTTP 入口与网关限流

通过 Spring Cloud Gateway 把外部请求路由到 Order、Storage、Account 和点赞服务，演示基于 Nacos 的 `lb://` 路由、跨域与
Sentinel 网关限流。Order 内部仍通过 OpenFeign、Nacos 服务发现与 LoadBalancer 调用分支；Gateway 不是它的内部 RPC 中转。

来源为 Spring Cloud Alibaba 官方 `2023.0.3.4` 的 integrated-example 场景；使用 Java
17，统一依赖版本和完整流程见[仓库根 README](../../README.md)。复制来源遵循 [Apache-2.0 LICENSE](../LICENSE)
与 [NOTICE](../NOTICE)。

## 启动与配置

- 在 IDE 运行 `com.alibaba.cloud.integration.gateway.GatewayApplication`，端口 `18085`，Nacos 服务名 `integrated-gateway`。
- 配置：[src/main/resources/application.yml](src/main/resources/application.yml)
  ；限流与跨域代码：[GatewayConfig.java](src/main/java/com/alibaba/cloud/integration/gateway/config/GatewayConfig.java)
  。默认使用本机 YAML，直接运行 main，无需修改 hosts 或发布 Nacos 配置。
- 服务发现使用 Nacos `127.0.0.1:8848`、组 `UU_INTEGRATED_EXAMPLE`。仅启动网关不会自动启动或证明下游服务可用。
- 下单需要 Order `18081`、Storage `18082`、Account `18083`，以及它们使用的 MySQL `127.0.0.1:3308`（`root/root`）、Seata TC
  `127.0.0.1:8091`（事务组 `uu_integrated_tx_group`）。先执行[统一初始化脚本](../sql/mysql.sql)，创建四个独立演示库。
- 页面还需 Frontend `18080`。点赞需另装 RocketMQ NameServer `127.0.0.1:9876` 和可达 Broker，再启动 Provider `18084` 与
  Consumer `18086`；本轮仅编译与下单验证，不表示 MQ 已联调。

## 从网关观察下单

[requests.http](requests.http) 优先覆盖下单链路，先查询两个资源，再下单并检查提交/回滚结果。以下路径保留完整业务前缀：

| 网关路径                                  | 下游服务                  |
|---------------------------------------|-----------------------|
| `/order/**`                           | `integrated-order`    |
| `/storage/**`                         | `integrated-storage`  |
| `/account/**`                         | `integrated-account`  |
| `/praise/rocketmq`、`/praise/sentinel` | `integrated-provider` |
| `/praise/query`                       | `integrated-consumer` |

```http
GET http://127.0.0.1:18085/account/?userId=admin

GET http://127.0.0.1:18085/storage/?commodityCode=1

POST http://127.0.0.1:18085/order/create
Content-Type: application/x-www-form-urlencoded

userId=admin&commodityCode=1&count=1
```

**POST 会消耗共用演示余额和库存，请逐条执行并核对基线，不要 Run All。** 在新库余额 `3`、库存 `100`、单价 `2` 的整数教学数据下，第一次购买
`1` 件预期 `code=2001`、余额 `1`、库存 `99`、新增 `1` 个订单；第二次因余额不足返回 `code=2003`，全局回滚后保持 `1/99`
，订单数不再增加。HTTP 200 不等于业务成功。

```sql
SELECT user_id, money FROM sca_integrated_account.account WHERE user_id = 'admin';
SELECT commodity_code, `count` FROM sca_integrated_storage.storage WHERE commodity_code = '1';
SELECT COUNT(*) AS order_count FROM sca_integrated_order.`order` WHERE user_id = 'admin';
```

初始化 SQL 幂等，不恢复消耗的数据。多份 requests.http 共用这些资源，重做前先读取实际基线，不要直接重置其他实验数据。分支扣减要求真实
XID，应通过 Order 发起事务。

## 点赞与限流的边界

`praiseItemSentinel` 路由配置每秒 `5` 的 Sentinel 网关规则；触发限流时返回 HTTP 429 与限流提示，应同时检查 HTTP
状态和响应内容。单次请求不能证明限流生效；直连 Provider 会绕过这里的网关限流。

`/praise/sentinel` 和 `/praise/rocketmq` 都发送 RocketMQ 消息；前者多了网关入口限流，不是无 MQ 的独立场景。消费者异步更新
`sca_integrated_praise.item`，不属于订单同步事务。未安装 RocketMQ 时先学习订单链路，不执行点赞页面或请求；上述内容是操作预期，不是本轮运行结果。

## 已验证范围

2026-11-15：3 个单元测试通过，覆盖 HTTP 429 中文 JSON、本机前端跨域放行及其他来源拒绝。
真实进程已通过网关完成订单提交、失败回滚和余额/库存查询，实例来自本机 Nacos 独立发现组。
实际 HTTP 还验证了跨域放行/拒绝，以及并发超过阈值时的 Sentinel 429 中文 JSON；该限流测试未启动点赞下游，不证明消息已投递。
网关使用 WebFlux，打包依赖已检查未引入 MVC 或 JDBC。RocketMQ 实际消息投递仍待安装组件后验证。
