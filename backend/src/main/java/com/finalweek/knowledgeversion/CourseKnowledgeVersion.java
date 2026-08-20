package com.finalweek.knowledgeversion;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import com.finalweek.course.Course;
import java.time.Instant;
import java.util.UUID;

public class CourseKnowledgeVersion {
    private UUID id;
    private UUID courseId;
    private long version;
    private KnowledgeVersionStatus status;
    private String materialSetHash;
    private UUID outlineId;
    private Instant createdAt;
    private Instant publishedAt;
    private String errorCode;

    protected CourseKnowledgeVersion() {}
    public CourseKnowledgeVersion(Course course, long version, String materialSetHash) {
        this.courseId = course.getId(); this.version = version; this.materialSetHash = materialSetHash;
        this.status = KnowledgeVersionStatus.GENERATING;
    }
    @BeforeInsert void created() { createdAt = Instant.now(); }
    public void publish(UUID outlineId) {
        this.outlineId = outlineId; this.status = KnowledgeVersionStatus.PUBLISHED;
        this.publishedAt = Instant.now(); this.errorCode = null;
    }
    public void supersede() { this.status = KnowledgeVersionStatus.SUPERSEDED; }
    public void retry() { this.status = KnowledgeVersionStatus.GENERATING; this.errorCode = null; }
    public void fail(String errorCode) { this.status = KnowledgeVersionStatus.FAILED; this.errorCode = errorCode; }
    public UUID getId() { return id; }
    public UUID getCourseId() { return courseId; }
    public long getVersion() { return version; }
    public KnowledgeVersionStatus getStatus() { return status; }
    public String getMaterialSetHash() { return materialSetHash; }
    public UUID getOutlineId() { return outlineId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getErrorCode() { return errorCode; }
}
