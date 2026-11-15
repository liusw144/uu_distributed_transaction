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

package com.alibaba.cloud.integration.consumer.mapper;

import java.sql.Timestamp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import org.springframework.stereotype.Repository;

/**
 * 仅通过参数化商品主键操作点赞记录，本机适配保留官方表结构。
 *
 * @author TrevorLink
 */
@Mapper
@Repository
public interface PraiseMapper {

    /**
     * 原子递增指定商品的点赞数，不承担消息去重。
     *
     * @param itemId     商品主键
     * @param updateTime 本次更新时间
     * @return 实际更新的行数
     */
    @Update("update item set praise = praise + 1, update_time = #{updateTime} where id = #{itemId}")
    int praiseItem(@Param("itemId") Integer itemId, @Param("updateTime") Timestamp updateTime);

    /**
     * 查询指定商品的点赞数。
     *
     * @param itemId 商品主键
     * @return 当前点赞数
     */
    @Select("select praise from item where id = #{itemId}")
    int getPraise(@Param("itemId") Integer itemId);

}
