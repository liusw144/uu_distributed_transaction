package com.alibaba.cloud.integration.consumer.service.impl;

import com.alibaba.cloud.integration.consumer.mapper.PraiseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证消费者服务在数据库操作前拒绝非法商品编号。
 *
 * @author liusw144
 */
class PraiseServiceImplTest {

    private PraiseMapper praiseMapper;

    private PraiseServiceImpl praiseService;

    @BeforeEach
    void setUp() {
        praiseMapper = mock(PraiseMapper.class);
        praiseService = new PraiseServiceImpl(praiseMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void invalidMessageDoesNotUpdateDatabase(Integer itemId) {
        assertThrows(IllegalArgumentException.class, () -> praiseService.praiseItem(itemId));
        verifyNoInteractions(praiseMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void invalidQueryDoesNotAccessDatabase(Integer itemId) {
        assertThrows(IllegalArgumentException.class, () -> praiseService.getPraise(itemId));
        verifyNoInteractions(praiseMapper);
    }
}
