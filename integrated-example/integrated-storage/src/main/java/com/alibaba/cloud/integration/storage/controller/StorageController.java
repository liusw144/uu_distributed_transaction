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

package com.alibaba.cloud.integration.storage.controller;

import com.alibaba.cloud.integration.common.BusinessException;
import com.alibaba.cloud.integration.common.Result;
import com.alibaba.cloud.integration.storage.dto.StorageDTO;
import com.alibaba.cloud.integration.storage.service.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存 JSON 写入及公开查询接口。 本地学习修改：用构造器注入，分支异常在本地事务回滚后转换为明确的失败响应。
 *
 * @author TrevorLink
 */
@RestController
@RequestMapping("/storage")
public class StorageController {

    private final StorageService storageService;

    /**
     * 创建库存接口。
     *
     * @param storageService 库存事务服务
     */
    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * 扣减指定商品库存。
     *
     * @param storageDTO 商品编码和正数数量
     * @return 库存扣减结果，失败响应供订单服务触发全局回滚
     */
    @PostMapping("/reduce-stock")
    public Result<?> reduceStock(@RequestBody StorageDTO storageDTO) {
        if (storageDTO == null) {
            return Result.failed("库存请求不能为空");
        }
        try {
            storageService.reduceStock(storageDTO.getCommodityCode(), storageDTO.getCount());
            return Result.success("");
        } catch (BusinessException exception) {
            return Result.failed(exception.getMessage());
        }
    }

    /**
     * 公开查询库存，不要求 XID。
     *
     * @param commodityCode 商品编码
     * @return 库存数量或查询失败说明
     */
    @GetMapping("/")
    public Result<?> getRemainCount(@RequestParam(value = "commodityCode", required = false) String commodityCode) {
        return storageService.getRemainCount(commodityCode);
    }
}
