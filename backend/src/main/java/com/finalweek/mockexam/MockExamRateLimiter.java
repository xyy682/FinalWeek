package com.finalweek.mockexam;

import com.finalweek.common.api.BusinessException;
import java.time.Duration;
import java.util.UUID;
import org.redisson.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MockExamRateLimiter {
    private final RedissonClient redisson;
    private final MockExamProperties properties;
    public MockExamRateLimiter(RedissonClient redisson, MockExamProperties properties) {
        this.redisson = redisson; this.properties = properties;
    }
    public void acquire(UUID userId) {
        var limiter = redisson.getRateLimiter("fw:rate:mock-exam:user:" + userId);
        limiter.trySetRate(RateType.OVERALL, properties.userRatePerMinute(), Duration.ofMinutes(1));
        if (!limiter.tryAcquire()) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                "MOCK_EXAM_RATE_LIMITED", "模拟卷生成过于频繁，请稍后重试");
    }
}
