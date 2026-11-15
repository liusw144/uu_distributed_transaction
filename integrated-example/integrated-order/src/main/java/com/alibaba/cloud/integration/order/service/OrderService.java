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

package com.alibaba.cloud.integration.order.service;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;

/**
 * 订单全局事务服务契约。 本地学习修改：明确参数校验、全局事务参与及失败语义。
 *
 * @author TrevorLink
 */
public interface OrderService {

    /**
     * 先扣库存，再扣账户，最后保存订单；任一失败都抛异常回滚全局事务。
     *
     * @param userId        非空用户标识
     * @param commodityCode 非空商品编码
     * @param count         正数数量，金额不得溢出
     * @return 含订单信息及学习用 XID 的成功响应
     * @throws BusinessException 参数非法或任一步业务执行失败
     */
    Result<?> createOrder(String userId, String commodityCode, Integer count) throws BusinessException;
}
