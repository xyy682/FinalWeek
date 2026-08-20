package com.finalweek.task;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity @Table(name = "failed_task")
public class FailedTask {
    @Id @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id") private BackgroundTask task;
    @Column(name = "message_id", nullable = false, unique = true, length = 120) private String messageId;
    @Enumerated(EnumType.STRING) @Column(name = "failure_stage", length = 40) private TaskStage failureStage;
    @Column(name = "failure_reason", nullable = false, length = 500) private String failureReason;
    @Column(name = "redeliver_count", nullable = false) private int redeliverCount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private FailedTaskStatus status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected FailedTask() {}
    public FailedTask(BackgroundTask task, String messageId, String reason) {
        this.task = task; this.messageId = messageId; this.failureStage = task.getCurrentStage();
        this.failureReason = reason; this.status = FailedTaskStatus.PENDING;
    }
    public void markRedelivered() { status = FailedTaskStatus.REDELIVERED; redeliverCount++; }
    public void markResolved() { status = FailedTaskStatus.RESOLVED; }
    public UUID getTaskId() { return task.getId(); }
    public FailedTaskStatus getStatus() { return status; }
    @PrePersist void created() { var now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void updated() { updatedAt = Instant.now(); }
}
