/*
 * Copyright 2013-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.integration.order.entity;

import java.sql.Timestamp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 订单响应及持久化对象。 本地学习修改：补充字段与访问器说明，保持原 JSON 属性，仅新增只读 XID。
 *
 * @author TrevorLink
 */
public class Order {

    /**
     * 订单主键。
     */
    private Integer id;

    /**
     * 用户标识。
     */
    private String userId;

    /**
     * 商品编码。
     */
    private String commodityCode;

    /**
     * 订单数量。
     */
    private Integer count;

    /**
     * 整数订单金额。
     */
    private Integer money;

    /**
     * 创建时间。
     */
    private Timestamp createTime;

    /**
     * 修改时间。
     */
    private Timestamp updateTime;

    /**
     * 仅用于响应和日志关联，不对应数据库列。
     */
    private final String xid;

    /**
     * 创建无事务标识的订单，保留上游构造方式。
     */
    public Order() {
        this(null);
    }

    /**
     * 创建带只读全局事务标识的订单。
     *
     * @param xid 全局事务标识
     */
    public Order(String xid) {
        this.xid = xid;
    }

    /**
     * 获取当前订单的全局事务标识。
     *
     * @return 学习用 XID，不持久化到订单表
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getXid() {
        return xid;
    }

    /**
     * 获取订单主键。
     *
     * @return 订单主键
     */
    public Integer getId() {
        return id;
    }

    /**
     * 设置订单主键。
     *
     * @param id 订单主键
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * 获取用户标识。
     *
     * @return 用户标识
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 设置用户标识。
     *
     * @param userId 用户标识
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 获取商品编码。
     *
     * @return 商品编码
     */
    public String getCommodityCode() {
        return commodityCode;
    }

    /**
     * 设置商品编码。
     *
     * @param commodityCode 商品编码
     */
    public void setCommodityCode(String commodityCode) {
        this.commodityCode = commodityCode;
    }

    /**
     * 获取订单数量。
     *
     * @return 订单数量
     */
    public Integer getCount() {
        return count;
    }

    /**
     * 设置订单数量。
     *
     * @param count 订单数量
     */
    public void setCount(Integer count) {
        this.count = count;
    }

    /**
     * 获取整数订单金额。
     *
     * @return 整数订单金额
     */
    public Integer getMoney() {
        return money;
    }

    /**
     * 设置整数订单金额。
     *
     * @param money 整数订单金额
     */
    public void setMoney(Integer money) {
        this.money = money;
    }

    /**
     * 获取创建时间。
     *
     * @return 创建时间
     */
    public Timestamp getCreateTime() {
        return createTime;
    }

    /**
     * 设置创建时间。
     *
     * @param createTime 创建时间
     */
    public void setCreateTime(Timestamp createTime) {
        this.createTime = createTime;
    }

    /**
     * 获取修改时间。
     *
     * @return 修改时间
     */
    public Timestamp getUpdateTime() {
        return updateTime;
    }

    /**
     * 设置修改时间。
     *
     * @param updateTime 修改时间
     */
    public void setUpdateTime(Timestamp updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "Order{" + "id=" + id + ", userId='" + userId + '\'' + ", commodityCode='" + commodityCode + '\''
            + ", count=" + count + ", money=" + money + ", createTime=" + createTime + ", updateTime=" + updateTime
            + ", xid='" + xid + '\'' + '}';
    }

}
