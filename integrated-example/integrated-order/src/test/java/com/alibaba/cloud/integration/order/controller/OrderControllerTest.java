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

package com.alibaba.cloud.integration.order.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.ResultEnum;
import com.alibaba.cloud.integration.order.service.OrderService;
import org.junit.jupiter.api.Test;

/**
 * 本地学习修改：验证事务代理包装异常仍转换成原业务失败响应。
 *
 * @author liusw144
 */
class OrderControllerTest {

    @Test
    void reportsWrappedBusinessFailureAfterTransactionProxyReturns() {
        OrderService orderService = mock(OrderService.class);
        when(orderService.createOrder("U1001", "SKU-001", 1))
            .thenThrow(new IllegalStateException("global rollback", new BusinessException("余额不足")));
        OrderController controller = new OrderController(orderService);
        var response = controller.createOrder("U1001", "SKU-001", 1);
        assertEquals(ResultEnum.COMMON_FAILED.getCode(), response.getCode());
        assertEquals("余额不足", response.getMessage());
    }
}
