package com.finalweek.task;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import java.time.Instant;
import java.util.UUID;

public class FailedTask {
    private UUID id;
    private UUID taskId;
    private String messageId;
    private TaskStage failureStage;
    private String failureReason;
    private int redeliverCount;
    private FailedTaskStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    protected FailedTask() {}
    public FailedTask(BackgroundTask task, String messageId, String reason) {
        this.taskId = task.getId(); this.messageId = messageId; this.failureStage = task.getCurrentStage();
        this.failureReason = reason; this.status = FailedTaskStatus.PENDING;
    }
    public void markRedelivered() { status = FailedTaskStatus.REDELIVERED; redeliverCount++; }
    public void markResolved() { status = FailedTaskStatus.RESOLVED; }
    public UUID getTaskId() { return taskId; }
    public FailedTaskStatus getStatus() { return status; }
    @BeforeInsert void created() { var now = Instant.now(); createdAt = now; updatedAt = now; }
    @BeforeUpdate void updated() { updatedAt = Instant.now(); }
}
