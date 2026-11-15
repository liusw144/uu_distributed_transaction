# integrated-account：账户分支

维护用户余额，是订单全局事务中的 Seata AT 分支。学习重点是本地 `@Transactional`、带余额条件的更新，以及 Order 经 OpenFeign
传入的 XID 如何使本次扣款加入同一个全局事务。Gateway 是外部入口，Order 调用账户时通过 Nacos 服务发现和 LoadBalancer 直达本服务。

来源为 Spring Cloud Alibaba 官方 `2023.0.3.4` 的 integrated-example 场景；本仓库使用 Java
17，统一依赖版本和完整操作入口见[仓库根 README](../../README.md)。复制来源遵循子树内的 [Apache-2.0 LICENSE](../LICENSE)
与 [NOTICE](../NOTICE)。

## 启动与配置

- 在 IDE 运行 `com.alibaba.cloud.integration.account.AccountServiceApplication`，端口 `18083`，Nacos 服务名
  `integrated-account`。
- 配置：[src/main/resources/application.yml](src/main/resources/application.yml)。默认读取本机 YAML，直接运行 main，无需修改
  hosts 或发布 Nacos 配置。
- 前置依赖：MySQL `127.0.0.1:3308`，用户名/密码 `root/root`；Nacos `127.0.0.1:8848`，发现组 `UU_INTEGRATED_EXAMPLE`；Seata TC
  `127.0.0.1:8091`，事务组 `uu_integrated_tx_group`。
- 先执行[统一初始化脚本](../sql/mysql.sql)，本服务使用 `sca_integrated_account`。验证下单还需启动 Storage `18082` 与 Order
  `18081`；从页面进入时再启动 Gateway `18085`、Frontend `18080`。订单链路不需要 RocketMQ。

## 请求与预期

[requests.http](requests.http) 按步骤读取基线、验证无 XID 扣款被拒绝，再通过 Order 观察成功提交与失败回滚。响应中的
`code=2001` 表示业务成功，`code=2003` 表示业务失败；HTTP 200 本身不能证明扣款成功。

```http
GET http://127.0.0.1:18083/account/?userId=admin

POST http://127.0.0.1:18083/account/reduce-balance
Content-Type: application/json

{"userId":"admin","price":2}
```

查询返回 `data` 中的余额。上面的直接扣款未携带真实全局事务 XID，预期业务失败、余额不变；不要手工伪造 XID 绕过这个约束。正常扣款由
Order 的 Feign 客户端发送同样的 JSON，并传播真实 XID。

```sql
SELECT user_id, money FROM sca_integrated_account.account WHERE user_id = 'admin';
SELECT COUNT(*) AS order_count FROM sca_integrated_order.`order` WHERE user_id = 'admin';
```

**下单请求会消耗演示余额和库存，请逐条执行，不能把多份 requests.http 当成互不影响的实验。** 新库种子余额 `3`、商品 `1` 库存
`100`、单价 `2`，金额使用整数教学单位：第一次购买 `1` 件后余额 `1`、库存 `99`、订单数增加 `1`
；第二次购买因余额不足失败，余额、库存和订单数保持第一次成功后的状态。用请求前后的实际查询与 SQL 比较判断回滚。

初始化 SQL 是幂等建表/补种子，不会把已消耗的资源恢复为 `3/100`。若基线已改变，先停止这组固定预期的请求，记录实际数据并参考根
README；不要直接重置其他实验的数据。

## 已验证范围

2026-11-15：6 个单元测试通过，覆盖非法金额、空用户、缺少 XID、账户不存在、条件更新失败与无 XID 查询。
真实三服务联调验证了 Feign XID 传播、成功扣款、余额不足触发全局回滚，以及直接无 XID 扣款被拒绝。
金额与三库 SQL 状态均已核对，详情见[订单验收](../integrated-order/README.md#已验证范围)。
