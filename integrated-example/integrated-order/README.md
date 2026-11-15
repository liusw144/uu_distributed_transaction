# integrated-order：全局事务入口

接收订单表单，开启 Seata 全局事务，依次扣库存、扣余额并保存订单。学习主线是 `@GlobalTransactional` 与各分支本地事务的关系，以及失败如何触发跨库回滚：

```text
HTTP 请求 → Order → OpenFeign → Nacos 服务发现 → LoadBalancer → Storage / Account
```

OpenFeign 在 HTTP 请求中传播 Seata XID，三个服务加入同一全局事务。Gateway 可以转发入口请求，但不承担 Order 到分支的内部
RPC；RocketMQ 异步点赞不在此同步调用链中。

来源为 Spring Cloud Alibaba 官方 `2023.0.3.4` 的 integrated-example 场景；使用 Java
17，统一依赖版本及完整操作入口见[仓库根 README](../../README.md)。复制来源遵循 [Apache-2.0 LICENSE](../LICENSE)
与 [NOTICE](../NOTICE)。

## 启动与配置

- 在 IDE 运行 `com.alibaba.cloud.integration.order.OrderServiceApplication`，端口 `18081`，Nacos 服务名
  `integrated-order`。
- 配置：[src/main/resources/application.yml](src/main/resources/application.yml)。默认使用本机 YAML，直接运行 main，无需修改
  hosts 或导入 Nacos 远程配置。
- 前置依赖：MySQL `127.0.0.1:3308`，用户名/密码 `root/root`；Nacos `127.0.0.1:8848`，发现组 `UU_INTEGRATED_EXAMPLE`；Seata TC
  `127.0.0.1:8091`，事务组 `uu_integrated_tx_group`。
- 先执行[统一初始化脚本](../sql/mysql.sql)。Order 使用 `sca_integrated_order`；Storage、Account 分别使用
  `sca_integrated_storage`、`sca_integrated_account`，三个业务库各有 AT `undo_log`。
- 先启动 Storage `18082`、Account `18083`，再启动本服务。页面实验另需 Gateway `18085` 和 Frontend `18080`，下单无需
  RocketMQ。

## 提交与回滚实验

**[requests.http](requests.http) 会消耗共用演示余额/库存。逐条执行，先核对实际基线，不要直接 Run All 或连续执行多份服务的下单示例。
** 新库种子数据是 `admin` 余额 `3`、商品 `1` 库存 `100`，单价 `2`，金额均为整数教学单位。

```http
GET http://127.0.0.1:18083/account/?userId=admin

GET http://127.0.0.1:18082/storage/?commodityCode=1

POST http://127.0.0.1:18081/order/create
Content-Type: application/x-www-form-urlencoded

userId=admin&commodityCode=1&count=1
```

1. 在 `3/100` 基线上第一次下单，预期 `code=2001`，返回订单；余额 `1`、库存 `99`，订单数增加 `1`。
2. 再提交同一表单，预期 `code=2003`，余额不足触发全局回滚；最终余额仍为 `1`、库存仍为 `99`，订单数不增加。
3. 请求脚本检查业务码与查询前后的数值，订单行数再用 SQL 比较。HTTP 200 不等于业务成功，日志中应能观察到
   Order、Storage、Account 的同一 XID。

```sql
SELECT user_id, money FROM sca_integrated_account.account WHERE user_id = 'admin';
SELECT commodity_code, `count` FROM sca_integrated_storage.storage WHERE commodity_code = '1';
SELECT COUNT(*) AS order_count FROM sca_integrated_order.`order` WHERE user_id = 'admin';
SELECT id, user_id, commodity_code, `count`, money FROM sca_integrated_order.`order` ORDER BY id DESC LIMIT 5;
```

Account/Storage 的扣减接口要求真实全局事务 XID，直接调用应失败；不要手工填 XID。此服务负责正常创建并传播 XID。

初始化 SQL 是幂等补种子，不会将余额/库存重置为 `3/100`。基线已被其他示例改变时，先停止本组固定预期请求并查看根
README，不要重置其他实验数据。这里列出的是预期行为，运行与验证结果以实际日志、HTTP 和 SQL 为准。

## 已验证范围

2026-11-15：12 个单元测试通过，覆盖参数与金额溢出校验、分支异常/空响应/失败业务码、订单保存失败和包装异常提示。
另通过本机 Gateway → Order → OpenFeign → Storage/Account 的真实三库联调：成功购买 2 件，余额 `12→8`、库存 `30→28`
，订单新增一行且数量为 `2`；
随后购买 5 件因余额不足失败，库存回滚到 `28`，余额仍为 `8`，订单数仍为 `1`。成功响应包含实际 Seata 2.0.0 XID。
联调使用独立测试资源并在撤销日志清理后删除，不消耗手工学习种子。全项目回归结果见[根 README](../../README.md#verification)。
