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
        var perUser = redisson.getRateLimiter("fw:rate:parse:v2:user:" + userId);
        perUser.trySetRate(RateType.OVERALL, properties.limits().parseUserRatePerMinute(), Duration.ofMinutes(1));
        if (!perUser.tryAcquire()) throw limited();
        var global = redisson.getRateLimiter("fw:rate:parse:v2:global");
        global.trySetRate(RateType.OVERALL, properties.limits().parseGlobalRatePerMinute(), Duration.ofMinutes(1));
        if (!global.tryAcquire()) throw limited();
    }

    public void acquirePlan(UUID userId) {
        var perUser = redisson.getRateLimiter("fw:rate:plan:user:" + userId);
        perUser.trySetRate(RateType.OVERALL, properties.limits().planUserRatePerMinute(), Duration.ofMinutes(1));
        if (!perUser.tryAcquire()) throw limited();
    }
    public void acquireChat(UUID userId) {
        var perUser = redisson.getRateLimiter("fw:rate:chat:user:" + userId);
        perUser.trySetRate(RateType.OVERALL, properties.limits().chatUserRatePerMinute(), Duration.ofMinutes(1));
        if (!perUser.tryAcquire()) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                "CHAT_RATE_LIMITED", "问答过于频繁，请稍后重试");
    }
    private BusinessException limited() {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TASK_PUBLISH_RATE_LIMITED",
                "短时间内提交的资料过多，请稍后重试；已经上传的文件不会丢失");
    }
}
