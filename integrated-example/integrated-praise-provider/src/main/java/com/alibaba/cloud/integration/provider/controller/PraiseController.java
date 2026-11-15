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

package com.alibaba.cloud.integration.provider.controller;

import java.util.Map;

import com.alibaba.cloud.integration.provider.message.PraiseMessage;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 点赞消息发送接口，本机适配保留官方 GET 路径和 StreamBridge 绑定。
 *
 * @author TrevorLink
 */
@RestController
@RequestMapping("/praise")
public class PraiseController {

    private static final String BINDING_NAME = "praise-output";

    private static final String INVALID_ITEM_MESSAGE = "商品 ID 必须为正整数";

    private final StreamBridge streamBridge;

    /**
     * 注入消息发送通道，不在接口中创建 Binder 或连接。
     *
     * @param streamBridge Spring Cloud Stream 消息桥接器
     */
    public PraiseController(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    /**
     * 发送一条点赞消息，数据库计数由 consumer 函数异步处理。
     *
     * @param itemId 正整数商品编号
     * @return 消息是否被发送通道接受，不代表消费完成
     */
    @GetMapping({"/rocketmq", "/sentinel"})
    public boolean praise(@RequestParam("itemId") Integer itemId) {
        if (itemId == null || itemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_ITEM_MESSAGE);
        }
        PraiseMessage message = new PraiseMessage();
        message.setItemId(itemId);
        Message<PraiseMessage> praiseMessage = MessageBuilder.withPayload(message).build();
        return streamBridge.send(BINDING_NAME, praiseMessage);
    }

    /**
     * 将缺失、非数字及非正整数参数统一为可供页面展示的中文错误。
     *
     * @return HTTP 400 和 JSON 错误信息
     */
    @ExceptionHandler({ResponseStatusException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> invalidItemId() {
        return ResponseEntity.badRequest()
            .body(Map.of("code", HttpStatus.BAD_REQUEST.value(), "message", INVALID_ITEM_MESSAGE));
    }

}
