package com.alibaba.cloud.integration.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.mockito.Mockito.mock;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * 验证限流响应和本机演示页面的跨域边界，无需启动注册中心或网关。
 *
 * @author liusw144
 */
class GatewayConfigTest {

    private GatewayConfig configuration;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        configuration = new GatewayConfig(mock(ObjectProvider.class), ServerCodecConfigurer.create(), 5, 1);
    }

    @AfterEach
    void resetGlobalCallback() {
        GatewayCallbackManager.resetBlockHandler();
    }

    @Test
    void blockedRequestReturns429WithReadableJson() {
        configuration.initBlockHandlers();
        WebTestClient client = WebTestClient
            .bindToRouterFunction(RouterFunctions.route(GET("/blocked"), request -> GatewayCallbackManager
                .getBlockHandler().handleRequest(request.exchange(), new FlowException("praiseItemSentinel"))))
            .build();
        client.get().uri("/blocked").exchange().expectStatus().isEqualTo(429).expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody().jsonPath("$.code").isEqualTo(429)
            .jsonPath("$.message")
            .value(message -> org.junit.jupiter.api.Assertions.assertTrue(message.toString().contains("限流")));
    }

    @Test
    void localFrontendOriginCanReadGatewayResponse() {
        corsClient().get().uri("/praise/query").header(HttpHeaders.ORIGIN, "http://127.0.0.1:18080").exchange()
            .expectStatus().isOk().expectHeader()
            .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:18080");
    }

    @Test
    void unrelatedOriginCannotReadGatewayResponse() {
        corsClient().get().uri("/praise/query").header(HttpHeaders.ORIGIN, "https://example.com").exchange()
            .expectStatus().isForbidden().expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    private WebTestClient corsClient() {
        return WebTestClient
            .bindToRouterFunction(
                RouterFunctions.route(GET("/praise/query"), request -> ServerResponse.ok().bodyValue(1)))
            .webFilter(configuration.corsFilter()).configureClient().baseUrl("http://127.0.0.1:18085").build();
    }
}
