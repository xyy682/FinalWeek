package com.finalweek.knowledgeversion;

import com.finalweek.course.Course;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "course_knowledge_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_knowledge_version_number", columnNames = {"course_id", "version"}),
        @UniqueConstraint(name = "uk_knowledge_version_material_set", columnNames = {"course_id", "material_set_hash"})
})
public class CourseKnowledgeVersion {
    @Id @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false) private Course course;
    @Column(nullable = false) private long version;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private KnowledgeVersionStatus status;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "material_set_hash", nullable = false, length = 64) private String materialSetHash;
    @Column(name = "outline_id") private UUID outlineId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "error_code", length = 80) private String errorCode;

    protected CourseKnowledgeVersion() {}
    public CourseKnowledgeVersion(Course course, long version, String materialSetHash) {
        this.course = course; this.version = version; this.materialSetHash = materialSetHash;
        this.status = KnowledgeVersionStatus.GENERATING;
    }
    @PrePersist void created() { createdAt = Instant.now(); }
    public void publish(UUID outlineId) {
        this.outlineId = outlineId; this.status = KnowledgeVersionStatus.PUBLISHED;
        this.publishedAt = Instant.now(); this.errorCode = null;
    }
    public void supersede() { this.status = KnowledgeVersionStatus.SUPERSEDED; }
    public void retry() { this.status = KnowledgeVersionStatus.GENERATING; this.errorCode = null; }
    public void fail(String errorCode) { this.status = KnowledgeVersionStatus.FAILED; this.errorCode = errorCode; }
    public UUID getId() { return id; }
    public UUID getCourseId() { return course.getId(); }
    public long getVersion() { return version; }
    public KnowledgeVersionStatus getStatus() { return status; }
    public String getMaterialSetHash() { return materialSetHash; }
    public UUID getOutlineId() { return outlineId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getErrorCode() { return errorCode; }
}
