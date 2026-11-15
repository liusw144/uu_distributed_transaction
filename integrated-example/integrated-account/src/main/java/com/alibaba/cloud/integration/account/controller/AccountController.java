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

package com.alibaba.cloud.integration.account.controller;

import com.alibaba.cloud.integration.account.dto.AccountDTO;
import com.alibaba.cloud.integration.account.service.AccountService;
import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户 JSON 扣款及公开查询接口。 本地学习修改：保留原路径及结果结构，扣款失败由调用方发起全局回滚。
 *
 * @author TrevorLink
 */
@RestController
@RequestMapping("/account")
public class AccountController {

    private final AccountService accountService;

    /**
     * 创建账户接口。
     *
     * @param accountService 账户事务服务
     */
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * 扣减账户余额。
     *
     * @param accountDTO 用户标识及正数扣款金额
     * @return 扣款结果，失败响应供订单服务触发全局回滚
     */
    @PostMapping("/reduce-balance")
    public Result<?> reduceBalance(@RequestBody AccountDTO accountDTO) {
        if (accountDTO == null) {
            return Result.failed("账户请求不能为空");
        }
        try {
            accountService.reduceBalance(accountDTO.getUserId(), accountDTO.getPrice());
            return Result.success("");
        } catch (BusinessException exception) {
            return Result.failed(exception.getMessage());
        }
    }

    /**
     * 公开查询余额，不要求 XID。
     *
     * @param userId 用户标识
     * @return 余额或查询失败说明
     */
    @GetMapping("/")
    public Result<?> getRemainAccount(@RequestParam(value = "userId", required = false) String userId) {
        return accountService.getRemainAccount(userId);
    }
}
