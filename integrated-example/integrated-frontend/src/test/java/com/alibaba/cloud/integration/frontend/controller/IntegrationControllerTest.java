package com.alibaba.cloud.integration.frontend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证演示入口导航和模板使用的本机网关地址。
 *
 * @author liusw144
 */
class IntegrationControllerTest {

    @Test
    void rootRedirectsToOrderDemo() throws Exception {
        client().perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/order"));
    }

    @Test
    void demoTemplateReceivesLocalGatewayAddress() throws Exception {
        client().perform(get("/sentinel")).andExpect(status().isOk())
            .andExpect(model().attribute("gatewayBaseUrl", "http://127.0.0.1:18085"));
    }

    private MockMvc client() {
        return MockMvcBuilders.standaloneSetup(new IntegrationController("http://127.0.0.1:18085"))
            .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html")).build();
    }
}
