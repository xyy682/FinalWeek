package com.finalweek.outline;

import com.finalweek.course.Course;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "outline")
public class Outline {
    @Id @UuidGenerator private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "course_id", nullable = false, unique = true)
    private Course course;
    @Column(name = "generation_version", nullable = false) private long generationVersion;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Outline() {}
    public Outline(Course course, long generationVersion) { this.course = course; publish(generationVersion); }
    public void publish(long version) { generationVersion = version; generatedAt = Instant.now(); updatedAt = generatedAt; }
    public void touch() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getCourseId() { return course.getId(); }
    public long getGenerationVersion() { return generationVersion; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
