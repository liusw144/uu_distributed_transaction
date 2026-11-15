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

package com.alibaba.cloud.integration.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.ResultEnum;
import com.alibaba.cloud.integration.storage.mapper.StorageMapper;
import io.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 本地学习修改：验证非法库存分支不能执行数据库操作，查询不依赖全局事务。
 *
 * @author liusw144
 */
class StorageServiceImplTest {

    private StorageMapper storageMapper;

    private StorageServiceImpl storageService;

    @BeforeEach
    void setUp() {
        storageMapper = mock(StorageMapper.class);
        storageService = new StorageServiceImpl(storageMapper);
        RootContext.bind("learning-storage-xid");
    }

    @AfterEach
    void clearTransactionContext() {
        RootContext.unbind();
    }

    @Test
    void rejectsInvalidQuantityBeforeAccessingMapper() {
        assertThrows(BusinessException.class, () -> storageService.reduceStock("SKU-001", -1));
        assertThrows(BusinessException.class, () -> storageService.reduceStock("SKU-001", 0));
        assertThrows(BusinessException.class, () -> storageService.reduceStock("SKU-001", null));
        verifyNoInteractions(storageMapper);
    }

    @Test
    void rejectsEmptyCommodityBeforeAccessingMapper() {
        assertThrows(BusinessException.class, () -> storageService.reduceStock(null, 1));
        assertThrows(BusinessException.class, () -> storageService.reduceStock(" ", 1));
        verifyNoInteractions(storageMapper);
    }

    @Test
    void rejectsWriteWithoutGlobalTransactionBeforeAccessingMapper() {
        RootContext.unbind();
        assertThrows(BusinessException.class, () -> storageService.reduceStock("SKU-001", 1));
        verifyNoInteractions(storageMapper);
    }

    @Test
    void rejectsMissingCommodityAsBusinessFailure() {
        when(storageMapper.getStock("missing")).thenReturn(null);
        assertThrows(BusinessException.class, () -> storageService.reduceStock("missing", 1));
        verify(storageMapper, never()).reduceStock(any(), any(), any());
    }

    @Test
    void rejectsConcurrentConditionalUpdateFailure() {
        when(storageMapper.getStock("SKU-001")).thenReturn(10);
        when(storageMapper.reduceStock(eq("SKU-001"), eq(1), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> storageService.reduceStock("SKU-001", 1));
    }

    @Test
    void permitsPublicQueryWithoutGlobalTransaction() {
        RootContext.unbind();
        when(storageMapper.getStock("SKU-001")).thenReturn(10);
        var response = storageService.getRemainCount("SKU-001");
        assertEquals(ResultEnum.SUCCESS.getCode(), response.getCode());
        assertEquals(10, response.getData());
    }
}
