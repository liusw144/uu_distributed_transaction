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

package com.alibaba.cloud.integration.gateway.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

/**
 * 本机 WebFlux 网关的限流和跨域配置，修改自官方 integrated-example。
 *
 * @author TrevorLink
 */
@Configuration
public class GatewayConfig {

    private final List<ViewResolver> viewResolvers;

    private final ServerCodecConfigurer serverCodecConfigurer;

    private final double praiseCount;

    private final long praiseIntervalSeconds;

    /**
     * 注入响应式视图解析器和编解码配置。
     *
     * @param viewResolversProvider 视图解析器提供者
     * @param serverCodecConfigurer WebFlux 编解码配置
     * @param praiseCount           每个窗口允许的点赞请求数量
     * @param praiseIntervalSeconds 限流窗口秒数
     */
    public GatewayConfig(ObjectProvider<List<ViewResolver>> viewResolversProvider,
                         ServerCodecConfigurer serverCodecConfigurer, @Value("${demo.sentinel.praise-count:5}") double praiseCount,
                         @Value("${demo.sentinel.praise-interval-seconds:1}") long praiseIntervalSeconds) {
        this.viewResolvers = viewResolversProvider.getIfAvailable(Collections::emptyList);
        this.serverCodecConfigurer = serverCodecConfigurer;
        this.praiseCount = praiseCount;
        this.praiseIntervalSeconds = praiseIntervalSeconds;
    }

    /**
     * 保留官方 Sentinel 网关过滤器。
     *
     * @return 网关全局过滤器
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public GlobalFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    /**
     * 初始化点赞路由的限流规则。
     */
    @PostConstruct
    public void initGatewayRules() {
        GatewayRuleManager.loadRules(Set.of(
            new GatewayFlowRule("praiseItemSentinel").setCount(praiseCount).setIntervalSec(praiseIntervalSeconds)));
    }

    /**
     * 注册响应式限流异常处理器。
     *
     * @return Sentinel 网关异常处理器
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler() {
        // Register the block exception handler for Spring Cloud Gateway.
        return new SentinelGatewayBlockExceptionHandler(viewResolvers, serverCodecConfigurer);
    }

    /**
     * 注册限流后的统一响应。
     */
    @PostConstruct
    public void initBlockHandlers() {
        GatewayCallbackManager.setBlockHandler((exchange, exception) -> ServerResponse
            .status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", HttpStatus.TOO_MANY_REQUESTS.value(), "message", "点赞请求过于频繁，已触发限流，请稍后重试")));
    }

    /**
     * 配置本机前端页面访问网关所需的跨域策略。
     *
     * @return WebFlux 跨域过滤器
     */
    @Bean
    public CorsWebFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedOrigins(List.of("http://127.0.0.1:18080", "http://localhost:18080"));
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }

}
