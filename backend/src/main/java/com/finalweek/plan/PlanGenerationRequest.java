package com.finalweek.plan;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "plan_generation_request")
public class PlanGenerationRequest {
    @Id @UuidGenerator private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "course_id", nullable = false) private UUID courseId;
    @Column(name = "idempotency_key", nullable = false, length = 80) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "char(64)") private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private PlanRequestStatus status;
    @Column(name = "expected_plan_version", nullable = false) private long expectedPlanVersion;
    @Column(name = "result_plan_version") private Long resultPlanVersion;
    @Column(name = "error_code", length = 80) private String errorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected PlanGenerationRequest() {}
    public PlanGenerationRequest(UUID userId, UUID courseId, String key, String hash, long expectedVersion) {
        this.userId = userId; this.courseId = courseId; this.idempotencyKey = key; this.requestHash = hash;
        this.expectedPlanVersion = expectedVersion; this.status = PlanRequestStatus.PENDING;
    }
    @PrePersist void created() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void updated() { updatedAt = Instant.now(); }
    public void retry(long expectedVersion) {
        expectedPlanVersion = expectedVersion; status = PlanRequestStatus.PENDING; errorCode = null;
    }
    public void succeed(long version) { status = PlanRequestStatus.SUCCEEDED; resultPlanVersion = version; errorCode = null; }
    public void fail(String code) { status = PlanRequestStatus.FAILED; errorCode = code; }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public String getRequestHash() { return requestHash; }
    public PlanRequestStatus getStatus() { return status; }
    public long getExpectedPlanVersion() { return expectedPlanVersion; }
    public Long getResultPlanVersion() { return resultPlanVersion; }
}
