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

package com.alibaba.cloud.integration.order.mapper;

import com.alibaba.cloud.integration.order.entity.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.springframework.stereotype.Repository;

/**
 * 订单持久层。 本地学习修改：补齐上游遗漏的数量列并保留生成主键，响应中的 xid 不映射到数据库。
 *
 * @author TrevorLink
 */
@Mapper
@Repository
public interface OrderMapper {

    /**
     * 保存订单并回填主键。
     *
     * @param order 已完成库存和余额扣减的订单
     * @return 实际插入行数
     */
    @Insert("INSERT INTO `order` (user_id, commodity_code, `count`, money, create_time, update_time) "
        + "VALUES (#{userId}, #{commodityCode}, #{count}, #{money}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyColumn = "id", keyProperty = "id")
    int saveOrder(Order order);
}
