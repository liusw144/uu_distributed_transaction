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

import java.sql.Timestamp;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;
import com.alibaba.cloud.integration.storage.mapper.StorageMapper;
import com.alibaba.cloud.integration.storage.service.StorageService;
import io.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存本地事务分支。 本地学习修改：写入必须携带全局事务，参数校验和条件更新共同防止反向增库存及超卖。
 *
 * @author TrevorLink
 */
@Service
public class StorageServiceImpl implements StorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageServiceImpl.class);

    private final StorageMapper storageMapper;

    /**
     * 创建库存分支。
     *
     * @param storageMapper 库存持久层
     */
    public StorageServiceImpl(StorageMapper storageMapper) {
        this.storageMapper = storageMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reduceStock(String commodityCode, Integer count) {
        if (commodityCode == null || commodityCode.isBlank()) {
            throw new BusinessException("商品编码不能为空");
        }
        if (count == null || count <= 0) {
            throw new BusinessException("扣减库存数量必须大于零");
        }
        if (RootContext.getXID() == null || RootContext.getXID().isBlank()) {
            throw new BusinessException("库存写入必须加入全局事务，缺少 XID");
        }
        LOGGER.info("[reduceStock] current XID: {}", RootContext.getXID());
        Integer stock = storageMapper.getStock(commodityCode);
        if (stock == null) {
            throw new BusinessException("商品不存在，请检查商品编码");
        }
        if (stock < count) {
            throw new BusinessException("库存不足");
        }

        // 预查用于友好提示；并发安全由 SQL 中的库存条件保证。
        int updateCount = storageMapper.reduceStock(commodityCode, count, new Timestamp(System.currentTimeMillis()));
        if (updateCount != 1) {
            throw new BusinessException("库存扣减失败，库存不足或数据已变化");
        }
    }

    @Override
    public Result<?> getRemainCount(String commodityCode) {
        if (commodityCode == null || commodityCode.isBlank()) {
            return Result.failed("商品编码不能为空");
        }
        Integer stock = storageMapper.getStock(commodityCode);
        if (stock == null) {
            return Result.failed("商品不存在，请检查商品编码");
        }
        return Result.success(stock);
    }
}
