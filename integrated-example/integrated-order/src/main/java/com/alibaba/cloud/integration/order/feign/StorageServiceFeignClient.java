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

package com.alibaba.cloud.integration.order.feign;

import com.alibaba.cloud.integration.common.Result;
import com.alibaba.cloud.integration.order.feign.dto.StorageDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 库存服务的 OpenFeign 契约。 本地学习修改：保留发现名及 JSON 接口，由 SCA 自动传播当前 XID。
 *
 * @author TrevorLink
 */
@FeignClient(name = "integrated-storage")
public interface StorageServiceFeignClient {

    /**
     * 请求库存分支完成扣减。
     *
     * @param productReduceStockDTO 扣减参数
     * @return 业务结果，只有 2001 表示成功
     */
    @PostMapping("/storage/reduce-stock")
    Result<?> reduceStock(@RequestBody StorageDTO productReduceStockDTO);
}
