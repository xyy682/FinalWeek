package com.finalweek.plan;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import com.finalweek.knowledgeversion.CourseKnowledgeVersion;
import com.finalweek.task.BackgroundTask;
import java.time.Instant;
import java.util.UUID;

public class PlanGenerationRequest {
    private UUID id;
    private UUID userId;
    private UUID courseId;
    private UUID knowledgeVersionId;
    private UUID backgroundTaskId;
    private String idempotencyKey;
    private String requestHash;
    private String requestJson;
    private PlanRequestStatus status;
    private long expectedPlanVersion;
    private Long resultPlanVersion;
    private String errorCode;
    private Instant createdAt;
    private Instant updatedAt;
    protected PlanGenerationRequest() {}
    public PlanGenerationRequest(UUID userId, UUID courseId, String key, String hash, long expectedVersion,
                                 CourseKnowledgeVersion knowledgeVersion, String requestJson) {
        this.userId = userId; this.courseId = courseId; this.idempotencyKey = key; this.requestHash = hash;
        this.expectedPlanVersion = expectedVersion; this.knowledgeVersionId = knowledgeVersion.getId();
        this.requestJson = requestJson; this.status = PlanRequestStatus.PENDING;
    }
    @BeforeInsert void created() { createdAt = Instant.now(); updatedAt = createdAt; }
    @BeforeUpdate void updated() { updatedAt = Instant.now(); }
    public void retry(long expectedVersion) {
        expectedPlanVersion = expectedVersion; status = PlanRequestStatus.PENDING; errorCode = null;
    }
    public void attachTask(BackgroundTask task) { this.backgroundTaskId = task.getId(); }
    public void succeed(long version) { status = PlanRequestStatus.SUCCEEDED; resultPlanVersion = version; errorCode = null; }
    public void fail(String code) { status = PlanRequestStatus.FAILED; errorCode = code; }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getKnowledgeVersionId() { return knowledgeVersionId; }
    public UUID getBackgroundTaskId() { return backgroundTaskId; }
    public String getRequestJson() { return requestJson; }
    public String getRequestHash() { return requestHash; }
    public PlanRequestStatus getStatus() { return status; }
    public long getExpectedPlanVersion() { return expectedPlanVersion; }
    public Long getResultPlanVersion() { return resultPlanVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
