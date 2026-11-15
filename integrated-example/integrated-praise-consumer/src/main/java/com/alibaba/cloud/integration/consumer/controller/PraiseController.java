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

package com.alibaba.cloud.integration.consumer.controller;

import java.util.Map;

import com.alibaba.cloud.integration.consumer.service.PraiseService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 本机学习示例的点赞数查询接口，修改自官方 integrated-example。
 *
 * @author TrevorLink
 */
@RestController
@RequestMapping("/praise")
public class PraiseController {

    private final PraiseService praiseService;

    /**
     * 注入点赞查询服务。
     *
     * @param praiseService 点赞持久化服务
     */
    public PraiseController(PraiseService praiseService) {
        this.praiseService = praiseService;
    }

    /**
     * 查询已异步写入数据库的点赞数。
     *
     * @param itemId 正整数商品编号
     * @return 当前点赞数
     */
    @GetMapping("/query")
    public Integer getPraise(@RequestParam("itemId") Integer itemId) {
        return praiseService.getPraise(itemId);
    }

    /**
     * 返回页面可展示的参数错误，避免非法编号进入查询。
     *
     * @return HTTP 400 和中文 JSON 错误
     */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> invalidItemId() {
        return ResponseEntity.badRequest()
            .body(Map.of("code", HttpStatus.BAD_REQUEST.value(), "message", "商品 ID 必须为正整数"));
    }

}
