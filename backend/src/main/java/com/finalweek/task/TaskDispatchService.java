package com.finalweek.task;

import com.finalweek.common.api.BusinessException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskDispatchService {
    private static final Logger log = LoggerFactory.getLogger(TaskDispatchService.class);
    private final TaskRateLimiter rateLimiter;
    private final TaskPublisher publisher;
    private final TaskStateService states;
    public TaskDispatchService(TaskRateLimiter rateLimiter, TaskPublisher publisher, TaskStateService states) {
        this.rateLimiter = rateLimiter; this.publisher = publisher; this.states = states;
    }

    public void dispatch(ParseTask task) {
        try {
            rateLimiter.acquire(task.getUserId());
            send(task);
        } catch (RuntimeException exception) {
            states.publishFailed(task.getId(), task.getExecutionRound(), code(exception), exception.getMessage());
            log.warn("Task publish failed taskId={}", task.getId(), exception);
        }
    }

    public void dispatchAfterRateLimit(ParseTask task) {
        try { send(task); }
        catch (RuntimeException exception) {
            states.publishFailed(task.getId(), task.getExecutionRound(), "MQ_PUBLISH_FAILED", exception.getMessage());
            log.warn("Task publish failed after acquired rate limit taskId={}", task.getId(), exception);
        }
    }

    public ParseTask republish(UUID userId, UUID taskId) {
        rateLimiter.acquire(userId);
        var task = states.prepareRepublish(userId, taskId);
        try { send(task); }
        catch (RuntimeException exception) {
            states.publishFailed(task.getId(), task.getExecutionRound(), "MQ_PUBLISH_FAILED", exception.getMessage());
            throw exception;
        }
        return states.owned(userId, taskId);
    }

    public ParseTask manualRetry(UUID userId, UUID taskId) {
        rateLimiter.acquire(userId);
        var task = states.prepareManualRetry(userId, taskId);
        try { send(task); }
        catch (RuntimeException exception) {
            states.publishFailed(task.getId(), task.getExecutionRound(), "MQ_PUBLISH_FAILED", exception.getMessage());
            throw exception;
        }
        return states.owned(userId, taskId);
    }

    private void send(ParseTask task) {
        publisher.publish(task);
        states.queued(task.getId(), task.getExecutionRound());
    }
    private String code(RuntimeException exception) {
        return exception instanceof BusinessException business ? business.code() : "MQ_PUBLISH_FAILED";
    }
}
