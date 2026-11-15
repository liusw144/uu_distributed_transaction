# integrated-praise-provider：异步点赞生产者

接收点赞请求，通过 Spring Cloud Stream 的 `StreamBridge` 向 RocketMQ 发送消息，由 Consumer 异步更新点赞数。学习重点是发送结果与消费完成的区别，以及
Gateway Sentinel 限流如何作用于消息入口；这条异步链路不参与 Order 的同步下单事务。

来源为 Spring Cloud Alibaba 官方 `2023.0.3.4` 的 integrated-example 场景；使用 Java
17，统一依赖版本和完整操作入口见[仓库根 README](../../README.md)。复制来源遵循 [Apache-2.0 LICENSE](../LICENSE)
与 [NOTICE](../NOTICE)。

## 启动与配置

- 在 IDE 运行 `com.alibaba.cloud.integration.provider.PraiseProviderApplication`，端口 `18084`。**Nacos
  服务名为 `integrated-provider`**，与目录名不同。
- 配置：[src/main/resources/application.yml](src/main/resources/application.yml)。默认使用本机 YAML，直接运行 main，无需修改
  hosts 或导入 Nacos 配置。
- 前置依赖：Nacos `127.0.0.1:8848`，组 `UU_INTEGRATED_EXAMPLE`；RocketMQ NameServer `127.0.0.1:9876` 与可达 Broker。只有
  NameServer 就绪不足以发送消息。
- Provider 不直接连接业务数据库。观察消费结果需 Consumer `18086` 与 MySQL `127.0.0.1:3308`（`root/root`
  ），先执行[统一初始化脚本](../sql/mysql.sql)，点赞库是 `sca_integrated_praise`。
- 网关限流还需 Gateway `18085`；页面演示另需 Frontend `18080`。**当前未安装 RocketMQ，本轮仅编译与下单测试，本模块及对应页面待另装
  MQ 后联调，不能据此认定已运行。**

## 请求与预期

[requests.http](requests.http) 供 MQ 安装完成后逐条执行：先读 Consumer 的基线，再发送一次，等待消费者处理后重查。*
*每次发送会改变演示点赞数；不要执行整批压测来验证单次链路。**

```http
GET http://127.0.0.1:18086/praise/query?itemId=1

GET http://127.0.0.1:18084/praise/rocketmq?itemId=1

GET http://127.0.0.1:18085/praise/sentinel?itemId=1
```

Provider 的 `/praise/rocketmq`、`/praise/sentinel` 两个端点都发送消息，响应是布尔值，不使用订单的 `Result` 包装。`true`
仅表示本次发送调用成功返回，不能单独证明消息已经被消费、数据库已经提交。Consumer 的查询返回整数，消息处理前后的值可能暂时相同；稍后重查并结合消费者日志与
SQL。

```sql
SELECT id, praise FROM sca_integrated_praise.item WHERE id = 1;
```

通过 Gateway 的 `/praise/sentinel` 才会经过 `praiseItemSentinel` 限流规则（每秒 `5`）；直连 `18084` 的同名接口不会触发该网关限流。网关超限返回
HTTP 429 与限流提示，必须检查状态和响应内容。一次成功请求不能证明限流生效。

该消息消费示例不提供严格一次处理的业务去重保证；不要把短时间内点赞数量变化当作精确吞吐或可靠性结论。初始化 SQL
幂等，不会重置已有点赞数。MQ 未就绪时保留这些请求用于后续学习，订单链路可独立使用，无需启动本服务。

## 已验证范围

2026-11-15：7 个单元测试通过，覆盖非法 itemId 不发送消息、HTTP 参数错误提示，以及正确使用原 `praise-output` 绑定和消息内容。
通过全项目编译、格式及 P3C 检查。本机未安装 RocketMQ，本服务未启动，尚未验证 Broker 接收、实际投递和消费结果。
