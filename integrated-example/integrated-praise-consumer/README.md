# integrated-praise-consumer：异步消费与查询

订阅 Provider 发出的点赞消息，通过 MyBatis 更新商品点赞数，并提供读取接口。学习重点是 Spring Cloud Stream
函数式消费者、异步处理与最终查询结果的时间差；它不接收 Order 的同步扣款/扣库存调用。

来源为 Spring Cloud Alibaba 官方 `2023.0.3.4` 的 integrated-example 场景；使用 Java
17，统一依赖版本和完整操作入口见[仓库根 README](../../README.md)。复制来源遵循 [Apache-2.0 LICENSE](../LICENSE)
与 [NOTICE](../NOTICE)。

## 启动与配置

- 在 IDE 运行 `com.alibaba.cloud.integration.consumer.PraiseConsumerApplication`，端口 `18086`。**Nacos
  服务名为 `integrated-consumer`**，与目录名不同。
- 配置：[src/main/resources/application.yml](src/main/resources/application.yml)
  ；消费函数：[ListenerAutoConfiguration.java](src/main/java/com/alibaba/cloud/integration/consumer/listener/ListenerAutoConfiguration.java)
  。默认使用本机 YAML，直接运行 main，无需修改 hosts 或导入 Nacos 配置。
- 前置依赖：Nacos `127.0.0.1:8848`，组 `UU_INTEGRATED_EXAMPLE`；MySQL `127.0.0.1:3308`，用户名/密码 `root/root`；RocketMQ
  NameServer `127.0.0.1:9876` 与可达 Broker。
- 先执行[统一初始化脚本](../sql/mysql.sql)，本服务使用 `sca_integrated_praise`。消息链路需 Provider `18084`；经网关还需
  Gateway `18085`，页面另需 Frontend `18080`。
- **当前未安装 RocketMQ，本轮仅编译与下单测试，本模块及对应页面待另装 MQ 后联调。** Consumer 启动会绑定消息消费，不能因为只想查询
  HTTP 就忽略 MQ 依赖。订单链路无需启动它。

## 请求与预期

[requests.http](requests.http) 面向 MQ 就绪后的手工联调：先记下基线，再通过 Provider 发送一条消息，等待消费后重复查询。*
*发送请求会改变点赞数据，查询端点本身只读。**

```http
GET http://127.0.0.1:18086/praise/query?itemId=1

GET http://127.0.0.1:18084/praise/rocketmq?itemId=1

GET http://127.0.0.1:18086/praise/query?itemId=1
```

Consumer 查询直接返回整数，不是 `{"code":...,"data":...}`。种子商品 `1` 应存在；若接口报错或 SQL 无记录，应先排查初始化与数据库配置，不能把空结果当成
`0` 或测试成功。Provider 返回 `true` 也不等于消费已经完成。

```sql
SELECT id, praise FROM sca_integrated_praise.item WHERE id = 1;
```

在无其他发送者且只消费一次的演示条件下，单条消息处理后点赞数预期比基线增加 `1`
。异步消息可能尚未处理，请稍后重查并结合消费者日志；此示例不提供严格一次业务处理保证，不宜用简单增量断言证明生产可靠性。初始化
SQL 幂等，不会重置已有点赞数。

Gateway `/praise/query` 路由到本服务，`/praise/rocketmq`、`/praise/sentinel` 路由到 Provider；`/praise/sentinel` 同样依赖
RocketMQ。异步点赞与 Seata 订单事务分开学习，这些步骤不是本轮 MQ 联调已通过的声明。

## 已验证范围

2026-11-15：6 个单元测试通过，覆盖空值、零和负数消息/查询拒绝且不访问数据库。
通过全项目编译、格式及 P3C 检查。本机未安装 RocketMQ，本服务未启动，尚未验证消费、重投和最终点赞数变化。
