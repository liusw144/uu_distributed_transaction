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

package com.alibaba.cloud.integration.consumer.listener;

import java.util.function.Consumer;

import com.alibaba.cloud.integration.consumer.message.PraiseMessage;
import com.alibaba.cloud.integration.consumer.service.PraiseService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

/**
 * 保留官方 consumer 函数式订阅链路，本机适配补充消费语义说明。
 *
 * @author liusw144
 */
@Configuration
public class ListenerAutoConfiguration {
    /**
     * 创建点赞消息消费者，异常继续交由 RocketMQ Binder 的失败策略处理。
     *
     * @param praiseService 点赞写入服务
     * @return 原 consumer 函数绑定
     */
    @Bean
    public Consumer<Message<PraiseMessage>> consumer(PraiseService praiseService) {
        return msg -> {
            praiseService.praiseItem(msg.getPayload().getItemId());
        };
    }
}
