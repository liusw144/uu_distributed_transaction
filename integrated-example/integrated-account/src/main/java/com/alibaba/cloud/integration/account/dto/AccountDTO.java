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

package com.alibaba.cloud.integration.account.dto;

/**
 * 账户扣款请求。 本地学习修改：补充字段与访问器说明，保持原 JSON 属性和数据类型。
 *
 * @author TrevorLink
 */
public class AccountDTO {

    /**
     * 用户标识。
     */
    private String userId;

    /**
     * 整数扣款金额，写入时必须大于零。
     */
    private Integer price;

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
     * 获取整数扣款金额，写入时必须大于零。
     *
     * @return 整数扣款金额，写入时必须大于零
     */
    public Integer getPrice() {
        return price;
    }

    /**
     * 设置整数扣款金额，写入时必须大于零。
     *
     * @param price 整数扣款金额，写入时必须大于零
     */
    public void setPrice(Integer price) {
        this.price = price;
    }

    @Override
    public String toString() {
        return "AccountDTO{" + "userId='" + userId + '\'' + ", price=" + price + '}';
    }

}
