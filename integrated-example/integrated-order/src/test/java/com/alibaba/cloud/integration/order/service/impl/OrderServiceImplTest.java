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

package com.alibaba.cloud.integration.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;
import com.alibaba.cloud.integration.common.ResultEnum;
import com.alibaba.cloud.integration.order.entity.Order;
import com.alibaba.cloud.integration.order.feign.AccountServiceFeignClient;
import com.alibaba.cloud.integration.order.feign.StorageServiceFeignClient;
import com.alibaba.cloud.integration.order.mapper.OrderMapper;
import io.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 本地学习修改：验证全局事务按库存、账户、订单顺序执行并将远程失败向外抛出。
 *
 * @author liusw144
 */
class OrderServiceImplTest {

    private OrderMapper orderMapper;

    private AccountServiceFeignClient accountService;

    private StorageServiceFeignClient storageService;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        accountService = mock(AccountServiceFeignClient.class);
        storageService = mock(StorageServiceFeignClient.class);
        orderService = new OrderServiceImpl(orderMapper, accountService, storageService);
        RootContext.bind("learning-order-xid");
    }

    @AfterEach
    void clearTransactionContext() {
        RootContext.unbind();
    }

    @Test
    void rejectsInvalidQuantityAndOverflowBeforeRemoteCalls() {
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", -1));
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 0));
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", null));
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", Integer.MAX_VALUE));
        verifyNoInteractions(orderMapper, accountService, storageService);
    }

    @Test
    void rejectsEmptyIdentifiersBeforeRemoteCalls() {
        assertThrows(BusinessException.class, () -> orderService.createOrder(null, "SKU-001", 1));
        assertThrows(BusinessException.class, () -> orderService.createOrder(" ", "SKU-001", 1));
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", null, 1));
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", " ", 1));
        verifyNoInteractions(orderMapper, accountService, storageService);
    }

    @Test
    void rejectsUnproxiedWriteWithoutGlobalTransaction() {
        RootContext.unbind();
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 1));
        verifyNoInteractions(orderMapper, accountService, storageService);
    }

    @Test
    void rejectsEmptyStorageResponseAsBusinessFailure() {
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 1));
        verifyNoInteractions(accountService, orderMapper);
    }

    @Test
    void rejectsUnknownStorageStatusAsBusinessFailure() {
        doReturn(Result.instance(503, "库存服务不可用", null)).when(storageService).reduceStock(any());
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 1));
        verifyNoInteractions(accountService, orderMapper);
    }

    @Test
    void rejectsNullStorageStatusAsBusinessFailure() {
        doReturn(Result.instance(null, "无状态", null)).when(storageService).reduceStock(any());
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 1));
        verifyNoInteractions(accountService, orderMapper);
    }

    @Test
    void convertsTransportFailureToBusinessFailure() {
        when(storageService.reduceStock(any())).thenThrow(new IllegalStateException("connection refused"));
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 1));
        verifyNoInteractions(accountService, orderMapper);
    }

    @Test
    void rejectsAccountFailureAfterStockReservation() {
        doReturn(Result.success("")).when(storageService).reduceStock(any());
        doReturn(Result.failed("余额不足")).when(accountService).reduceBalance(any());
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 1));
        verifyNoInteractions(orderMapper);
    }

    @Test
    void rejectsEmptyAccountResponseAfterStockReservation() {
        doReturn(Result.success("")).when(storageService).reduceStock(any());
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 1));
        verifyNoInteractions(orderMapper);
    }

    @Test
    void rejectsOrderInsertFailureAfterBothBranches() {
        doReturn(Result.success("")).when(storageService).reduceStock(any());
        doReturn(Result.success("")).when(accountService).reduceBalance(any());
        when(orderMapper.saveOrder(any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> orderService.createOrder("U1001", "SKU-001", 1));
    }

    @Test
    void createsOrderOnlyAfterBothBranchesSucceed() {
        doReturn(Result.success("")).when(storageService).reduceStock(any());
        doReturn(Result.success("")).when(accountService).reduceBalance(any());
        when(orderMapper.saveOrder(any())).thenReturn(1);
        var response = orderService.createOrder("U1001", "SKU-001", 3);
        var calls = inOrder(storageService, accountService, orderMapper);
        calls.verify(storageService).reduceStock(any());
        calls.verify(accountService).reduceBalance(any());
        calls.verify(orderMapper).saveOrder(any());
        assertEquals(ResultEnum.SUCCESS.getCode(), response.getCode());
        Order order = (Order) response.getData();
        assertEquals(3, order.getCount());
        assertEquals(6, order.getMoney());
        assertEquals("learning-order-xid", order.getXid());
    }
}
