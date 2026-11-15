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

package com.alibaba.cloud.integration.storage.dto;

/**
 * 库存扣减请求。 本地学习修改：补充字段与访问器说明，保持原 JSON 属性和数据类型。
 *
 * @author TrevorLink
 */
public class StorageDTO {

    /**
     * 商品编码。
     */
    private String commodityCode;

    /**
     * 扣减数量，写入时必须大于零。
     */
    private Integer count;

    /**
     * 获取商品编码。
     *
     * @return 商品编码
     */
    public String getCommodityCode() {
        return commodityCode;
    }

    /**
     * 设置商品编码。
     *
     * @param commodityCode 商品编码
     */
    public void setCommodityCode(String commodityCode) {
        this.commodityCode = commodityCode;
    }

    /**
     * 获取扣减数量，写入时必须大于零。
     *
     * @return 扣减数量，写入时必须大于零
     */
    public Integer getCount() {
        return count;
    }

    /**
     * 设置扣减数量，写入时必须大于零。
     *
     * @param count 扣减数量，写入时必须大于零
     */
    public void setCount(Integer count) {
        this.count = count;
    }

    @Override
    public String toString() {
        return "StorageDTO{" + "commodityCode='" + commodityCode + '\'' + ", count=" + count + '}';
    }

}
