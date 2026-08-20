package com.finalweek.outline;

import com.finalweek.course.Course;
import com.finalweek.knowledgeversion.CourseKnowledgeVersion;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "outline")
public class Outline {
    @Id @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "course_id", nullable = false)
    private Course course;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_version_id", nullable = false, unique = true)
    private CourseKnowledgeVersion knowledgeVersion;
    @Column(name = "generation_version", nullable = false) private long generationVersion;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Outline() {}
    public Outline(Course course, CourseKnowledgeVersion knowledgeVersion, long generationVersion) {
        this.course = course; this.knowledgeVersion = knowledgeVersion; publish(generationVersion);
    }
    public void publish(long version) { generationVersion = version; generatedAt = Instant.now(); updatedAt = generatedAt; }
    public void touch() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getCourseId() { return course.getId(); }
    public UUID getKnowledgeVersionId() { return knowledgeVersion.getId(); }
    public long getGenerationVersion() { return generationVersion; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
