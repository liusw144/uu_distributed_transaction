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

package com.alibaba.cloud.integration.frontend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.view.RedirectView;

/**
 * 本机学习页面路由，修改自官方 integrated-example。
 *
 * @author HuangSir
 * @date 2022-09-08 14:00
 */
@Controller
public class IntegrationController {
    private final String gatewayBaseUrl;

    /**
     * 注入浏览器访问网关的地址。
     *
     * @param gatewayBaseUrl 本机网关地址，默认端口 18085
     */
    public IntegrationController(@Value("${demo.gateway-base-url:http://127.0.0.1:18085}") String gatewayBaseUrl) {
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    /**
     * 为三种演示模板提供同一网关地址。
     *
     * @return 浏览器可访问的网关地址
     */
    @ModelAttribute("gatewayBaseUrl")
    public String gatewayBaseUrl() {
        return gatewayBaseUrl;
    }

    /**
     * 将根地址引导到下单演示页面。
     *
     * @return 下单页面重定向
     */
    @GetMapping("/")
    public RedirectView index() {
        RedirectView redirectView = new RedirectView("/order");
        redirectView.setExposeModelAttributes(false);
        return redirectView;
    }

    /**
     * 展示分布式下单示例。
     *
     * @return 下单模板
     */
    @GetMapping("/order")
    public String order() {
        return "order";
    }

    /**
     * 展示 RocketMQ 异步点赞示例。
     *
     * @return 消息队列模板
     */
    @GetMapping("/rocketmq")
    public String rocketmq() {
        return "rocketmq";
    }

    /**
     * 展示 Sentinel 限流示例。
     *
     * @return 网关限流模板
     */
    @GetMapping("/sentinel")
    public String sentinel() {
        return "sentinel";
    }

}
