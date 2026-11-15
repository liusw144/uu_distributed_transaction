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

import java.sql.Timestamp;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;
import com.alibaba.cloud.integration.common.ResultEnum;
import com.alibaba.cloud.integration.order.entity.Order;
import com.alibaba.cloud.integration.order.feign.AccountServiceFeignClient;
import com.alibaba.cloud.integration.order.feign.StorageServiceFeignClient;
import com.alibaba.cloud.integration.order.feign.dto.AccountDTO;
import com.alibaba.cloud.integration.order.feign.dto.StorageDTO;
import com.alibaba.cloud.integration.order.mapper.OrderMapper;
import com.alibaba.cloud.integration.order.service.OrderService;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单全局事务入口。 本地学习修改：保持库存、账户、订单的执行顺序，任一步失败均抛异常，由 Seata 回滚各分支。
 *
 * @author TrevorLink
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderServiceImpl.class);

    /**
     * 保留官方示例的整数单价，金额单位与账户表一致。
     */
    private static final int UNIT_PRICE = 2;

    private final OrderMapper orderMapper;

    private final AccountServiceFeignClient accountService;

    private final StorageServiceFeignClient storageService;

    /**
     * 创建订单事务服务。
     *
     * @param orderMapper    订单持久层
     * @param accountService 账户远程服务
     * @param storageService 库存远程服务
     */
    public OrderServiceImpl(OrderMapper orderMapper, AccountServiceFeignClient accountService,
                            StorageServiceFeignClient storageService) {
        this.orderMapper = orderMapper;
        this.accountService = accountService;
        this.storageService = storageService;
    }

    @Override
    @GlobalTransactional(name = "integrated-create-order", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public Result<?> createOrder(String userId, String commodityCode, Integer count) {
        validateOrder(userId, commodityCode, count);
        if (RootContext.getXID() == null || RootContext.getXID().isBlank()) {
            throw new BusinessException("创建订单必须通过全局事务代理，缺少 XID");
        }
        String xid = RootContext.getXID();
        LOGGER.info("[createOrder] current XID: {}", xid);

        // SCA 的 Feign 拦截器将当前 XID 传给库存和账户，两个本地事务据此注册到同一全局事务。
        reduceStock(commodityCode, count);
        int price = count * UNIT_PRICE;
        reduceBalance(userId, price);

        Order order = new Order(xid);
        order.setUserId(userId);
        order.setCommodityCode(commodityCode);
        order.setCount(count);
        order.setMoney(price);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        order.setCreateTime(now);
        order.setUpdateTime(now);
        if (orderMapper.saveOrder(order) != 1) {
            throw new BusinessException("订单保存失败");
        }
        LOGGER.info("[createOrder] orderId: {}, XID: {}", order.getId(), xid);
        return Result.success(order);
    }

    private void validateOrder(String userId, String commodityCode, Integer count) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("用户标识不能为空");
        }
        if (commodityCode == null || commodityCode.isBlank()) {
            throw new BusinessException("商品编码不能为空");
        }
        if (count == null || count <= 0) {
            throw new BusinessException("订单数量必须大于零");
        }
        if (count > Integer.MAX_VALUE / UNIT_PRICE) {
            throw new BusinessException("订单金额超出整数范围");
        }
    }

    private void reduceStock(String commodityCode, Integer count) {
        StorageDTO storageDTO = new StorageDTO();
        storageDTO.setCommodityCode(commodityCode);
        storageDTO.setCount(count);
        Result<?> response;
        try {
            response = storageService.reduceStock(storageDTO);
        } catch (Exception exception) {
            throw new BusinessException("库存服务调用失败", exception);
        }
        requireSuccess(response, "库存服务");
    }

    private void reduceBalance(String userId, Integer price) {
        AccountDTO accountDTO = new AccountDTO();
        accountDTO.setUserId(userId);
        accountDTO.setPrice(price);
        Result<?> response;
        try {
            response = accountService.reduceBalance(accountDTO);
        } catch (Exception exception) {
            throw new BusinessException("账户服务调用失败", exception);
        }
        requireSuccess(response, "账户服务");
    }

    private void requireSuccess(Result<?> response, String serviceName) {
        if (response == null) {
            throw new BusinessException(serviceName + "返回空响应");
        }
        if (!ResultEnum.SUCCESS.getCode().equals(response.getCode())) {
            throw new BusinessException(serviceName + "调用失败：" + response.getMessage());
        }
    }
}
