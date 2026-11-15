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

package com.alibaba.cloud.integration.account.service;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;

/**
 * 账户分支事务与余额查询服务契约。 本地学习修改：明确参数校验、全局事务参与及失败语义。
 *
 * @author TrevorLink
 */
public interface AccountService {

    /**
     * 在当前全局事务中扣减账户余额。
     *
     * @param userId 非空用户标识
     * @param price  正数金额
     * @throws BusinessException 参数非法、缺少 XID、账户不存在或余额不足
     */
    void reduceBalance(String userId, Integer price) throws BusinessException;

    /**
     * 公开查询账户余额，不要求 XID。
     *
     * @param userId 用户标识
     * @return 余额或查询失败说明
     */
    Result<?> getRemainAccount(String userId);
}
