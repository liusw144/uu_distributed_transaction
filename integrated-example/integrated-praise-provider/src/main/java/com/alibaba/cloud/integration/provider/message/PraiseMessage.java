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

package com.alibaba.cloud.integration.provider.message;

/**
 * 保持官方消息结构的点赞载荷；本机适配仅补充说明，不新增消息协议。
 *
 * @author TrevorLink
 */
public class PraiseMessage {

    /**
     * 被点赞的正整数商品编号。
     */
    private Integer itemId;

    /**
     * 获取被点赞的商品编号。
     *
     * @return 商品编号
     */
    public Integer getItemId() {
        return itemId;
    }

    /**
     * 设置被点赞的商品编号，合法性由发送和消费边界检查。
     *
     * @param itemId 商品编号
     */
    public void setItemId(Integer itemId) {
        this.itemId = itemId;
    }

    @Override
    public String toString() {
        return "PraiseMessage{" + "itemId=" + itemId + '}';
    }

}
