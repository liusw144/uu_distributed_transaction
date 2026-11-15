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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.integration.account.mapper.AccountMapper;
import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.ResultEnum;
import io.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 本地学习修改：验证非法扣款不访问数据库，余额查询可独立调用。
 *
 * @author liusw144
 */
class AccountServiceImplTest {

    private AccountMapper accountMapper;

    private AccountServiceImpl accountService;

    @BeforeEach
    void setUp() {
        accountMapper = mock(AccountMapper.class);
        accountService = new AccountServiceImpl(accountMapper);
        RootContext.bind("learning-account-xid");
    }

    @AfterEach
    void clearTransactionContext() {
        RootContext.unbind();
    }

    @Test
    void rejectsInvalidAmountBeforeAccessingMapper() {
        assertThrows(BusinessException.class, () -> accountService.reduceBalance("U1001", -2));
        assertThrows(BusinessException.class, () -> accountService.reduceBalance("U1001", 0));
        assertThrows(BusinessException.class, () -> accountService.reduceBalance("U1001", null));
        verifyNoInteractions(accountMapper);
    }

    @Test
    void rejectsEmptyUserBeforeAccessingMapper() {
        assertThrows(BusinessException.class, () -> accountService.reduceBalance(null, 2));
        assertThrows(BusinessException.class, () -> accountService.reduceBalance(" ", 2));
        verifyNoInteractions(accountMapper);
    }

    @Test
    void rejectsWriteWithoutGlobalTransactionBeforeAccessingMapper() {
        RootContext.unbind();
        assertThrows(BusinessException.class, () -> accountService.reduceBalance("U1001", 2));
        verifyNoInteractions(accountMapper);
    }

    @Test
    void rejectsMissingAccountAsBusinessFailure() {
        when(accountMapper.getBalance("missing")).thenReturn(null);
        assertThrows(BusinessException.class, () -> accountService.reduceBalance("missing", 2));
        verify(accountMapper, never()).reduceBalance(any(), any(), any());
    }

    @Test
    void rejectsConcurrentConditionalUpdateFailure() {
        when(accountMapper.getBalance("U1001")).thenReturn(10);
        when(accountMapper.reduceBalance(eq("U1001"), eq(2), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> accountService.reduceBalance("U1001", 2));
    }

    @Test
    void permitsPublicQueryWithoutGlobalTransaction() {
        RootContext.unbind();
        when(accountMapper.getBalance("U1001")).thenReturn(10);
        var response = accountService.getRemainAccount("U1001");
        assertEquals(ResultEnum.SUCCESS.getCode(), response.getCode());
        assertEquals(10, response.getData());
    }
}
