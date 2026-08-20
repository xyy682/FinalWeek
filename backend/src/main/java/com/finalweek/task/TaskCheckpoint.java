package com.finalweek.task;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity @Table(name = "task_checkpoint", uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "stage"}))
public class TaskCheckpoint {
    @Id @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id") private BackgroundTask task;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private TaskStage stage;
    @Column(name = "result_object_key", length = 512) private String resultObjectKey;
    @Column(name = "result_json", columnDefinition = "json") private String resultJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private CheckpointStatus status;
    @Column(name = "completed_at", nullable = false) private Instant completedAt;
    protected TaskCheckpoint() {}
    public TaskCheckpoint(BackgroundTask task, TaskStage stage, String objectKey, String resultJson) {
        if (!task.getTaskType().supports(stage)) throw new PermanentTaskException(
                "CHECKPOINT_STAGE_INVALID", "任务类型不允许写入该 checkpoint");
        this.task = task; this.stage = stage; this.resultObjectKey = objectKey; this.resultJson = resultJson;
        this.status = CheckpointStatus.COMPLETED; this.completedAt = Instant.now();
    }
    public TaskStage getStage() { return stage; }
    public String getResultObjectKey() { return resultObjectKey; }
    public String getResultJson() { return resultJson; }
}
