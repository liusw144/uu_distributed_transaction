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

package com.alibaba.cloud.integration.account.mapper;

import java.sql.Timestamp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * 账户持久层。 本地学习修改：所有金额均参数绑定，条件扣减负责并发余额约束。
 *
 * @author TrevorLink
 */
@Mapper
@Repository
public interface AccountMapper {

    /**
     * 查询账户余额。
     *
     * @param userId 用户标识
     * @return 账户余额，账户不存在时为 null
     */
    @Select("SELECT money FROM account WHERE user_id = #{userId}")
    Integer getBalance(@Param("userId") String userId);

    /**
     * 原子扣减足额余额。
     *
     * @param userId     用户标识
     * @param price      正数扣款金额
     * @param updateTime 修改时间
     * @return 实际更新行数，零行表示账户不存在或余额不足
     */
    @Update("UPDATE account SET money = money - #{price}, update_time = #{updateTime} "
        + "WHERE user_id = #{userId} AND money >= #{price}")
    int reduceBalance(@Param("userId") String userId, @Param("price") Integer price,
                      @Param("updateTime") Timestamp updateTime);
}
