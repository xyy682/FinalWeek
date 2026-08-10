package com.finalweek.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.RequestIdFilter;
import com.finalweek.common.api.SecurityErrorWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

class RedisAvailabilityFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/login", "/api/v1/courses", "/api/v1/courses/1/chat"})
    void loginAuthenticatedAndAiApisFailClosedWithStable503(String path) throws Exception {
        var redis = mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("offline")).when(redis).hasKey("fw:health:redis");
        var filter = new RedisAvailabilityFilter(redis, new SecurityErrorWriter(new ObjectMapper().findAndRegisterModules()));
        var request = new MockHttpServletRequest("GET", path);
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-123");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SERVICE_REDIS_UNAVAILABLE", "request-123");
    }
}
