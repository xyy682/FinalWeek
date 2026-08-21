package com.finalweek.task;

import java.time.Instant;
import java.util.UUID;

public class TaskCheckpoint {
    private UUID id;
    private UUID taskId;
    private TaskStage stage;
    private String resultObjectKey;
    private String resultJson;
    private CheckpointStatus status;
    private Instant completedAt;
    protected TaskCheckpoint() {}
    public TaskCheckpoint(BackgroundTask task, TaskStage stage, String objectKey, String resultJson) {
        if (!task.getTaskType().supports(stage)) throw new PermanentTaskException(
                "CHECKPOINT_STAGE_INVALID", "任务类型不允许写入该 checkpoint");
        this.taskId = task.getId(); this.stage = stage; this.resultObjectKey = objectKey; this.resultJson = resultJson;
        this.status = CheckpointStatus.COMPLETED; this.completedAt = Instant.now();
    }
    public TaskStage getStage() { return stage; }
    public UUID getTaskId() { return taskId; }
    public String getResultObjectKey() { return resultObjectKey; }
    public String getResultJson() { return resultJson; }
}
