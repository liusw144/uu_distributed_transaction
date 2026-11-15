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

package com.alibaba.cloud.integration.storage.mapper;

import java.sql.Timestamp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * 库存持久层。 本地学习修改：使用参数绑定和库存条件，使并发扣减不能超卖。
 *
 * @author TrevorLink
 */
@Mapper
@Repository
public interface StorageMapper {

    /**
     * 查询商品库存。
     *
     * @param commodityCode 商品编码
     * @return 库存数量，商品不存在时为 null
     */
    @Select("SELECT `count` FROM storage WHERE commodity_code = #{commodityCode}")
    Integer getStock(@Param("commodityCode") String commodityCode);

    /**
     * 原子扣减足量库存。
     *
     * @param commodityCode 商品编码
     * @param count         正数扣减数量
     * @param updateTime    修改时间
     * @return 实际更新行数，零行表示商品不存在或库存不足
     */
    @Update("UPDATE storage SET `count` = `count` - #{count}, update_time = #{updateTime} "
        + "WHERE commodity_code = #{commodityCode} AND `count` >= #{count}")
    int reduceStock(@Param("commodityCode") String commodityCode, @Param("count") Integer count,
                    @Param("updateTime") Timestamp updateTime);
}
