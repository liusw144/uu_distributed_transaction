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

package com.alibaba.cloud.integration.account.service.impl;

import java.sql.Timestamp;

import com.alibaba.cloud.integration.account.mapper.AccountMapper;
import com.alibaba.cloud.integration.account.service.AccountService;
import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;
import io.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户本地事务分支。 本地学习修改：拒绝非正金额和缺少 XID 的扣款，用条件更新保证余额不被并发扣成负数。
 *
 * @author TrevorLink
 */
@Service
public class AccountServiceImpl implements AccountService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountServiceImpl.class);

    private final AccountMapper accountMapper;

    /**
     * 创建账户分支。
     *
     * @param accountMapper 账户持久层
     */
    public AccountServiceImpl(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reduceBalance(String userId, Integer price) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("用户标识不能为空");
        }
        if (price == null || price <= 0) {
            throw new BusinessException("扣款金额必须大于零");
        }
        if (RootContext.getXID() == null || RootContext.getXID().isBlank()) {
            throw new BusinessException("账户写入必须加入全局事务，缺少 XID");
        }
        LOGGER.info("[reduceBalance] current XID: {}", RootContext.getXID());
        Integer balance = accountMapper.getBalance(userId);
        if (balance == null) {
            throw new BusinessException("账户不存在，请检查用户标识");
        }
        if (balance < price) {
            throw new BusinessException("余额不足");
        }

        // 余额预查用于错误提示，SQL 的余额条件负责并发扣款约束。
        int updateCount = accountMapper.reduceBalance(userId, price, new Timestamp(System.currentTimeMillis()));
        if (updateCount != 1) {
            throw new BusinessException("账户扣款失败，余额不足或数据已变化");
        }
    }

    @Override
    public Result<?> getRemainAccount(String userId) {
        if (userId == null || userId.isBlank()) {
            return Result.failed("用户标识不能为空");
        }
        Integer balance = accountMapper.getBalance(userId);
        if (balance == null) {
            return Result.failed("账户不存在，请检查用户标识");
        }
        return Result.success(balance);
    }
}
