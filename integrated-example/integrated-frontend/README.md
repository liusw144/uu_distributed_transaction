# integrated-frontend：三个场景的页面入口

使用 Thymeleaf 提供下单、Sentinel 和 RocketMQ 演示页面。学习重点是浏览器请求经过 Gateway 进入业务服务，以及页面显示的 HTTP
结果与数据库最终状态如何对应。Frontend 只渲染页面，不协调 Seata 事务，也不代替消息消费者。

来源为 Spring Cloud Alibaba 官方 `2023.0.3.4` 的 integrated-example 场景；使用 Java
17，统一依赖版本和完整步骤见[仓库根 README](../../README.md)。复制来源遵循 [Apache-2.0 LICENSE](../LICENSE)
与 [NOTICE](../NOTICE)。

## 启动与配置

- 在 IDE 运行 `com.alibaba.cloud.integration.frontend.FrontendApplication`，HTTP 端口 `18080`。
- 配置：[src/main/resources/application.yml](src/main/resources/application.yml)
  ；页面位于 [src/main/resources/templates](src/main/resources/templates)。默认使用本机 YAML，直接运行 main，无需修改 hosts。
- 页面通过本机 Gateway `http://127.0.0.1:18085` 调用业务接口，地址由 `demo.gateway-base-url` 配置；交互脚本在本服务静态目录中。只打开
  HTML 不代表下游服务已就绪。
- 下单需启动 Gateway `18085`、Order `18081`、Storage `18082`、Account `18083`；它们依赖 Nacos `127.0.0.1:8848`（组
  `UU_INTEGRATED_EXAMPLE`）、Seata TC `127.0.0.1:8091`（事务组 `uu_integrated_tx_group`）、MySQL `127.0.0.1:3308`（`root/root`
  ）。先执行[统一初始化脚本](../sql/mysql.sql)。
- 点赞页面还需另装 RocketMQ NameServer `127.0.0.1:9876` 与可达 Broker，启动 Provider `18084`、Consumer `18086`；点赞数据使用
  `sca_integrated_praise`。当前仅编译与下单验证，不表示两个 MQ 服务或页面已联调。

## 页面与验证

[requests.http](requests.http) 只 GET 三个页面并检查 HTML 返回；它不会点击按钮、执行页面 JavaScript，也不能证明下单、消息消费或限流正常。

| 页面   | 地址                                | 操作影响                  |
|------|-----------------------------------|-----------------------|
| 下单   | `http://127.0.0.1:18080/order`    | 提交按钮会创建真实演示订单并消耗余额/库存 |
| 网关限流 | `http://127.0.0.1:18080/sentinel` | 点击会发起点赞，仍依赖 RocketMQ  |
| 异步点赞 | `http://127.0.0.1:18080/rocketmq` | 按钮会发起一批请求，改变点赞数       |

```http
GET http://127.0.0.1:18080/order
```

**下单按钮使用共用演示数据，重复点击会消耗余额和库存。** 操作前先查询 `admin` 余额和商品 `1` 库存；新库基线是 `3/100`，单价
`2`，金额使用整数教学单位。第一次购买 `1` 件预期余额 `1`、库存 `99`、订单数增加 `1`；第二次因余额不足失败，Seata
全局回滚后资源和订单数保持第一次成功后的状态。页面中的失败提示与 HTTP 200 可以同时出现，需结合业务码、SQL 和日志判断。

```sql
SELECT user_id, money FROM sca_integrated_account.account WHERE user_id = 'admin';
SELECT commodity_code, `count` FROM sca_integrated_storage.storage WHERE commodity_code = '1';
SELECT COUNT(*) AS order_count FROM sca_integrated_order.`order` WHERE user_id = 'admin';
```

初始化 SQL
幂等，不恢复已消耗的种子数据；基线改变时先停止固定预期演示，不要直接重置其他数据。[Order 请求示例](../integrated-order/requests.http)
可逐条观察提交与回滚。内部调用是 Order → OpenFeign → Nacos/LoadBalancer → Storage/Account，并传播 XID；Gateway
是外部入口，点赞消息不参与订单同步链路。未安装 RocketMQ 时不要操作两个点赞页面的按钮。

## 已验证范围

2026-11-15：2 个单元测试通过，覆盖首页跳转和模板网关地址；4 个本地 JavaScript 文件通过 Node 语法检查。
本轮已验证本机主类配置启动、三张页面 HTTP 响应、首页跳转和渲染后的网关地址，页面使用本地资源。
后端实际三库提交/回滚见[订单验收](../integrated-order/README.md#已验证范围)。
消息页面的真实发送与消费未联调。
