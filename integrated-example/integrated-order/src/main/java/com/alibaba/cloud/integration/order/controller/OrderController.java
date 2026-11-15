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

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;
import com.alibaba.cloud.integration.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单表单接口。 本地学习修改：异常离开事务代理后再转换响应，并识别 Seata 代理包装的业务异常。
 *
 * @author TrevorLink
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    /**
     * 创建订单接口。
     *
     * @param orderService 全局事务服务
     */
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 通过表单参数创建订单。
     *
     * @param userId        用户标识
     * @param commodityCode 商品编码
     * @param count         正数订单数量
     * @return 原有结果结构，成功时包含订单及学习用 XID
     */
    @PostMapping("/create")
    public Result<?> createOrder(@RequestParam("userId") String userId,
                                 @RequestParam("commodityCode") String commodityCode, @RequestParam("count") Integer count) {
        try {
            Result<?> result = orderService.createOrder(userId, commodityCode, count);
            if (result == null) {
                return Result.failed("订单服务返回空响应");
            }
            return result;
        } catch (Exception exception) {
            LOGGER.error("创建订单失败，userId={}, commodityCode={}", userId, commodityCode, exception);
            // 全局事务适配器可能包装业务异常；在代理完成回滚后保留原业务提示。
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof BusinessException) {
                    return Result.failed(cause.getMessage());
                }
                cause = cause.getCause();
            }
            return Result.failed("创建订单失败，请查看服务日志");
        }
    }
}
