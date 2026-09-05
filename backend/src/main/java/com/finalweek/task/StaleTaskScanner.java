package com.finalweek.task;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class StaleTaskScanner {
    private final BackgroundTaskRepository tasks;
    private final TaskStateService states;
    private final TaskDispatchService dispatcher;
    private final TaskProperties properties;

    public StaleTaskScanner(BackgroundTaskRepository tasks, TaskStateService states,
                            TaskDispatchService dispatcher, TaskProperties properties) {
        this.tasks = tasks;
        this.states = states;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${finalweek.task.stale-scan-interval}")
    public void reconcile() {
        var now = Instant.now();
        var pendingBefore = now.minus(properties.stalePendingThreshold());
        for (var task : tasks.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                TaskStatus.PENDING_PUBLISH, pendingBefore)) {
            states.publishFailed(task.getId(), task.getExecutionRound(), "PUBLISH_CONFIRM_MISSING",
                    "任务发布后未在期限内收到可靠确认");
        }
        for (var task : tasks.findExpiredProcessingLeases(now)) {
            states.recoverExpiredLease(task.getId(), task.getExecutionRound(), task.getProcessingOwner(), now)
                    .ifPresent(dispatcher::dispatchRecovered);
        }
    }
}