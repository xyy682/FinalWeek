package com.finalweek.task;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskLeaseHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(TaskLeaseHeartbeat.class);
    private final BackgroundTaskRepository tasks;
    private final TaskProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "task-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public TaskLeaseHeartbeat(BackgroundTaskRepository tasks, TaskProperties properties) {
        this.tasks = tasks;
        this.properties = properties;
    }

    public Lease start(UUID taskId, int executionRound, String owner) {
        Duration lease = properties.processingLease();
        Duration interval = properties.leaseHeartbeatInterval();
        if (lease == null || interval == null || lease.isZero() || lease.isNegative()
                || interval.isZero() || interval.isNegative() || interval.compareTo(lease) >= 0) {
            throw new IllegalStateException("任务租约必须为正数，且心跳间隔必须小于租约时长");
        }
        var active = new AtomicBoolean(true);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            if (!active.get()) return;
            try {
                int renewed = tasks.renewProcessingLease(taskId, executionRound, owner, Instant.now().plus(lease));
                if (renewed != 1) {
                    active.set(false);
                    log.warn("Task lease ownership lost taskId={} executionRound={} owner={}",
                            taskId, executionRound, owner);
                }
            } catch (RuntimeException exception) {
                log.warn("Task lease heartbeat failed taskId={} executionRound={} owner={}",
                        taskId, executionRound, owner, exception);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return () -> {
            active.set(false);
            future.cancel(false);
        };
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}