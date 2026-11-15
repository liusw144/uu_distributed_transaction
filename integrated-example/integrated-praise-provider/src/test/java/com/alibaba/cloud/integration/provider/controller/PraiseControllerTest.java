package com.alibaba.cloud.integration.provider.controller;

import com.alibaba.cloud.integration.provider.message.PraiseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证参数错误不会触发真实消息发送，合法请求保留原绑定和消息结构。
 *
 * @author liusw144
 */
class PraiseControllerTest {

    private StreamBridge streamBridge;

    private PraiseController controller;

    @BeforeEach
    void setUp() {
        streamBridge = mock(StreamBridge.class);
        controller = new PraiseController(streamBridge);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void invalidItemIdDoesNotSendMessage(Integer itemId) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> controller.praise(itemId));
        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(streamBridge);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "abc", ""})
    void invalidHttpParameterHasReadableJson(String itemId) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/praise/rocketmq").param("itemId", itemId)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(streamBridge);
    }

    @Test
    void validPraiseUsesOriginalStreamBindingAndPayload() {
        when(streamBridge.send(eq("praise-output"), any())).thenReturn(true);
        assertTrue(controller.praise(1));
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq("praise-output"), captor.capture());
        PraiseMessage payload = (PraiseMessage) captor.getValue().getPayload();
        assertEquals(1, payload.getItemId());
    }
}
