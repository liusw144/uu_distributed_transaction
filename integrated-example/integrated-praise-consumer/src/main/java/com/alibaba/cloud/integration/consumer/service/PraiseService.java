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

package com.alibaba.cloud.integration.consumer.service;

/**
 * 点赞写入和查询边界；本机适配在数据库调用前校验商品编号。
 *
 * @author TrevorLink
 */
public interface PraiseService {

    /**
     * 为指定商品递增一次点赞数。
     * <p>
     * 这是官方示例的计数流程，消息重投可能重复计数，不是生产幂等方案。
     * </p>
     *
     * @param itemId 正整数商品编号
     */
    void praiseItem(Integer itemId);

    /**
     * 查询商品当前点赞数。
     *
     * @param itemId 正整数商品编号
     * @return 当前点赞数
     */
    int getPraise(Integer itemId);

}
