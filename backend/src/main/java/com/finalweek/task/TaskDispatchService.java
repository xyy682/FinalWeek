package com.finalweek.task;

import com.finalweek.common.api.BusinessException;
import java.util.UUID;
import com.finalweek.mockexam.MockExamRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskDispatchService {
    private static final Logger log = LoggerFactory.getLogger(TaskDispatchService.class);
    private final TaskRateLimiter rateLimiter;
    private final TaskPublisher publisher;
    private final TaskStateService states;
    private final MockExamRateLimiter mockExamRateLimiter;
    public TaskDispatchService(TaskRateLimiter rateLimiter, TaskPublisher publisher, TaskStateService states,
                               MockExamRateLimiter mockExamRateLimiter) {
        this.rateLimiter = rateLimiter; this.publisher = publisher; this.states = states;
        this.mockExamRateLimiter = mockExamRateLimiter;
    }

    public void dispatch(BackgroundTask task) {
        try {
            rateLimiter.acquire(task.getUserId());
            send(task);
        } catch (RuntimeException exception) {
            states.publishFailed(task.getId(), task.getExecutionRound(), code(exception), exception.getMessage());
            log.warn("Task publish failed taskId={}", task.getId(), exception);
        }
    }

    public void dispatchAfterRateLimit(BackgroundTask task) {
        try { send(task); }
        catch (RuntimeException exception) {
            states.publishFailed(task.getId(), task.getExecutionRound(), "MQ_PUBLISH_FAILED", exception.getMessage());
            log.warn("Task publish failed after acquired rate limit taskId={}", task.getId(), exception);
        }
    }

    public BackgroundTask republish(UUID userId, UUID taskId) {
        var existing = states.owned(userId, taskId);
        switch (existing.getTaskType()) {
            case GENERATE_PLAN -> rateLimiter.acquirePlan(userId);
            case ANSWER_CHAT -> rateLimiter.acquireChat(userId);
            case GENERATE_MOCK_EXAM -> mockExamRateLimiter.acquire(userId);
            default -> rateLimiter.acquire(userId);
        }
        var task = states.prepareRepublish(userId, taskId);
        try { send(task); }
        catch (RuntimeException exception) {
            states.publishFailed(task.getId(), task.getExecutionRound(), "MQ_PUBLISH_FAILED", exception.getMessage());
            throw exception;
        }
        return states.owned(userId, taskId);
    }

    public void rejectBeforePublish(BackgroundTask task, RuntimeException exception) {
        states.publishFailed(task.getId(), task.getExecutionRound(), code(exception), exception.getMessage());
    }

    public BackgroundTask manualRetry(UUID userId, UUID taskId) {
        var existing = states.owned(userId, taskId);
        switch (existing.getTaskType()) {
            case GENERATE_PLAN -> rateLimiter.acquirePlan(userId);
            case ANSWER_CHAT -> rateLimiter.acquireChat(userId);
            case GENERATE_MOCK_EXAM -> mockExamRateLimiter.acquire(userId);
            default -> rateLimiter.acquire(userId);
        }
        var task = states.prepareManualRetry(userId, taskId);
        try { send(task); }
        catch (RuntimeException exception) {
            states.publishFailed(task.getId(), task.getExecutionRound(), "MQ_PUBLISH_FAILED", exception.getMessage());
            throw exception;
        }
        return states.owned(userId, taskId);
    }

    private void send(BackgroundTask task) {
        long started = System.nanoTime();
        publisher.publish(task);
        states.queued(task.getId(), task.getExecutionRound());
        log.info("Task submitted taskId={} taskType={} executionRound={} submitDurationMs={}", task.getId(),
                task.getTaskType(), task.getExecutionRound(), (System.nanoTime() - started) / 1_000_000);
    }
    private String code(RuntimeException exception) {
        return exception instanceof BusinessException business ? business.code() : "MQ_PUBLISH_FAILED";
    }
}
