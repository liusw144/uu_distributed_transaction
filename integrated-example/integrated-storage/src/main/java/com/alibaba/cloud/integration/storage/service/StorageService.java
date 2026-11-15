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

package com.alibaba.cloud.integration.storage.service;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;

/**
 * 库存分支事务与查询服务契约。 本地学习修改：明确参数校验、全局事务参与及失败语义。
 *
 * @author TrevorLink
 */
public interface StorageService {

    /**
     * 在当前全局事务中扣减库存。
     *
     * @param commodityCode 非空商品编码
     * @param orderCount    正数数量
     * @throws BusinessException 参数非法、缺少 XID、商品不存在或库存不足
     */
    void reduceStock(String commodityCode, Integer orderCount) throws BusinessException;

    /**
     * 公开查询剩余库存，不要求 XID。
     *
     * @param commodityCode 商品编码
     * @return 剩余数量或查询失败说明
     */
    Result<?> getRemainCount(String commodityCode);
}
