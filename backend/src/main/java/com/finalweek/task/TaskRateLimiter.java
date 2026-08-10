package com.finalweek.task;

import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import java.time.Duration;
import java.util.UUID;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TaskRateLimiter {
    private final RedissonClient redisson;
    private final FinalWeekProperties properties;
    public TaskRateLimiter(RedissonClient redisson, FinalWeekProperties properties) {
        this.redisson = redisson; this.properties = properties;
    }
    public void acquire(UUID userId) {
        var perUser = redisson.getRateLimiter("fw:rate:parse:user:" + userId);
        perUser.trySetRate(RateType.OVERALL, properties.limits().parseUserRatePerMinute(), Duration.ofMinutes(1));
        if (!perUser.tryAcquire()) throw limited();
        var global = redisson.getRateLimiter("fw:rate:parse:global");
        global.trySetRate(RateType.OVERALL, properties.limits().parseGlobalRatePerMinute(), Duration.ofMinutes(1));
        if (!global.tryAcquire()) throw limited();
    }
    private BusinessException limited() {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TASK_PUBLISH_RATE_LIMITED", "任务发布过于频繁，请稍后重试");
    }
}
