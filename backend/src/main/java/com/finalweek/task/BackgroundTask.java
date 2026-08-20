package com.finalweek.task;

import com.finalweek.material.Material;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "background_task")
public class BackgroundTask {
    @Id @UuidGenerator private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "course_id", nullable = false) private UUID courseId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "material_id") private Material material;
    @Column(name = "business_id", nullable = false) private UUID businessId;
    @Enumerated(EnumType.STRING) @Column(name = "task_type", nullable = false, length = 30) private TaskType taskType;
    @Column(name = "visible_in_global_drawer", nullable = false) private boolean visibleInGlobalDrawer;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private TaskStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "current_stage", length = 40) private TaskStage currentStage;
    @Column(name = "publish_attempt_count", nullable = false) private int publishAttemptCount;
    @Column(name = "delivery_attempt_count", nullable = false) private int deliveryAttemptCount;
    @Column(name = "api_attempt_count", nullable = false) private int apiAttemptCount;
    @Column(name = "manual_retry_count", nullable = false) private int manualRetryCount;
    @Column(name = "execution_round", nullable = false) private int executionRound;
    @Column(name = "generation_version") private Long generationVersion;
    @Column(name = "business_key", nullable = false, unique = true, length = 180) private String businessKey;
    @Column(name = "error_code", length = 80) private String errorCode;
    @Column(name = "error_message", length = 500) private String errorMessage;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected BackgroundTask() {}
    public BackgroundTask(UUID userId, UUID courseId, Material material) {
        this.userId = userId; this.courseId = courseId; this.material = material;
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
    @PrePersist void created() { var now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void updated() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getMaterialId() { return material == null ? null : material.getId(); }
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
