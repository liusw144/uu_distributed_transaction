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

package com.alibaba.cloud.integration.consumer.service.impl;

import java.sql.Timestamp;

import com.alibaba.cloud.integration.consumer.mapper.PraiseMapper;
import com.alibaba.cloud.integration.consumer.service.PraiseService;

import org.springframework.stereotype.Service;

/**
 * 点赞持久化实现，本机适配在访问 Mapper 前检查商品编号。
 * <p>
 * 保留官方的原子计数更新，重复消息仍会重复计数，不提供生产幂等保证。
 * </p>
 *
 * @author TrevorLink
 */
@Service
public class PraiseServiceImpl implements PraiseService {

    private final PraiseMapper praiseMapper;

    /**
     * 注入点赞数据访问器。
     *
     * @param praiseMapper 参数化商品主键的 Mapper
     */
    public PraiseServiceImpl(PraiseMapper praiseMapper) {
        this.praiseMapper = praiseMapper;
    }

    @Override
    public void praiseItem(Integer itemId) {
        validateItemId(itemId);
        Timestamp updateTime = new Timestamp(System.currentTimeMillis());
        praiseMapper.praiseItem(itemId, updateTime);
    }

    @Override
    public int getPraise(Integer itemId) {
        validateItemId(itemId);
        return praiseMapper.getPraise(itemId);
    }

    /**
     * 在消息消费和 HTTP 查询入口下方统一保护数据库访问边界。
     *
     * @param itemId 待校验的商品编号
     */
    private void validateItemId(Integer itemId) {
        if (itemId == null || itemId <= 0) {
            throw new IllegalArgumentException("商品 ID 必须为正整数");
        }
    }

}
