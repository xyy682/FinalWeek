package com.finalweek.common.config;

import com.finalweek.common.api.SecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RedisAvailabilityFilter extends OncePerRequestFilter {

    private static final String HEALTH_KEY = "fw:health:redis";

    private final StringRedisTemplate redisTemplate;
    private final SecurityErrorWriter errorWriter;

    public RedisAvailabilityFilter(StringRedisTemplate redisTemplate, SecurityErrorWriter errorWriter) {
        this.redisTemplate = redisTemplate;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.equals("/api/v1/system/ping");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            redisTemplate.hasKey(HEALTH_KEY);
            chain.doFilter(request, response);
        } catch (RuntimeException exception) {
            errorWriter.write(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "SERVICE_REDIS_UNAVAILABLE", "服务暂时不可用，请稍后重试");
        }
    }
}

