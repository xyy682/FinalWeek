package com.finalweek.task;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import com.finalweek.material.Material;
import java.time.Instant;
import java.util.UUID;

public class BackgroundTask {
    private UUID id;
    private UUID userId;
    private UUID courseId;
    private UUID materialId;
    private UUID businessId;
    private TaskType taskType;
    private boolean visibleInGlobalDrawer;
    private TaskStatus status;
    private TaskStage currentStage;
    private int publishAttemptCount;
    private int deliveryAttemptCount;
    private int apiAttemptCount;
    private int manualRetryCount;
    private int executionRound;
    private Long generationVersion;
    private String businessKey;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected BackgroundTask() {}
    public BackgroundTask(UUID userId, UUID courseId, Material material) {
        this.userId = userId; this.courseId = courseId; this.materialId = material.getId();
        this.businessId = material.getId(); this.visibleInGlobalDrawer = true;
        this.taskType = TaskType.PARSE_MATERIAL; this.status = TaskStatus.PENDING_PUBLISH;
        this.currentStage = TaskStage.UPLOADED; this.publishAttemptCount = 1;
        this.businessKey = "PARSE_MATERIAL:" + material.getId();
    }
    public BackgroundTask(UUID userId, UUID courseId, UUID knowledgeVersionId, long generationVersion) {
        this.userId = userId; this.courseId = courseId; this.businessId = knowledgeVersionId;
        this.generationVersion = generationVersion; this.visibleInGlobalDrawer = true;
        this.taskType = TaskType.GENERATE_OUTLINE; this.status = TaskStatus.PENDING_PUBLISH;
        this.publishAttemptCount = 1;
        this.businessKey = "GENERATE_OUTLINE:" + knowledgeVersionId;
    }
    public BackgroundTask(UUID userId, UUID courseId, TaskType type, UUID businessId,
                          boolean visibleInGlobalDrawer) {
        if (type == TaskType.PARSE_MATERIAL || type == TaskType.GENERATE_OUTLINE)
            throw new IllegalArgumentException("请使用该任务类型的专用构造方法");
        this.userId = userId; this.courseId = courseId; this.businessId = businessId;
        this.taskType = type; this.visibleInGlobalDrawer = visibleInGlobalDrawer;
        this.status = TaskStatus.PENDING_PUBLISH; this.publishAttemptCount = 1;
        this.businessKey = type.name() + ":" + businessId;
    }
    @BeforeInsert void created() { var now = Instant.now(); createdAt = now; updatedAt = now; }
    @BeforeUpdate void updated() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getMaterialId() { return materialId; }
    public UUID getBusinessId() { return businessId; }
    public TaskType getTaskType() { return taskType; }
    public boolean isVisibleInGlobalDrawer() { return visibleInGlobalDrawer; }
    public TaskStatus getStatus() { return status; }
    public TaskStage getCurrentStage() { return currentStage; }
    public int getPublishAttemptCount() { return publishAttemptCount; }
    public int getDeliveryAttemptCount() { return deliveryAttemptCount; }
    public int getApiAttemptCount() { return apiAttemptCount; }
    public int getManualRetryCount() { return manualRetryCount; }
    public int getExecutionRound() { return executionRound; }
    public Long getGenerationVersion() { return generationVersion; }
    public String getBusinessKey() { return businessKey; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
