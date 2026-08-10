package com.finalweek.task;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class StaleTaskScanner {
    private final ParseTaskRepository tasks;
    private final TaskStateService states;
    private final TaskProperties properties;
    public StaleTaskScanner(ParseTaskRepository tasks, TaskStateService states, TaskProperties properties) {
        this.tasks = tasks; this.states = states; this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${finalweek.task.stale-scan-interval}")
    public void reconcile() {
        var before = Instant.now().minus(properties.stalePendingThreshold());
        for (var task : tasks.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(TaskStatus.PENDING_PUBLISH, before)) {
            states.publishFailed(task.getId(), task.getExecutionRound(), "PUBLISH_CONFIRM_MISSING",
                    "任务发布后未在期限内收到可靠确认");
        }
    }
}
