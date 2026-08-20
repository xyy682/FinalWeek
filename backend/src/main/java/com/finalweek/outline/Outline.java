package com.finalweek.outline;

import com.finalweek.course.Course;
import com.finalweek.knowledgeversion.CourseKnowledgeVersion;
import java.time.Instant;
import java.util.UUID;

public class Outline {
    private UUID id;
    private UUID courseId;
    private UUID knowledgeVersionId;
    private long generationVersion;
    private Instant generatedAt;
    private Instant updatedAt;
    protected Outline() {}
    public Outline(Course course, CourseKnowledgeVersion knowledgeVersion, long generationVersion) {
        this.courseId = course.getId(); this.knowledgeVersionId = knowledgeVersion.getId(); publish(generationVersion);
    }
    public void publish(long version) { generationVersion = version; generatedAt = Instant.now(); updatedAt = generatedAt; }
    public void touch() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getCourseId() { return courseId; }
    public UUID getKnowledgeVersionId() { return knowledgeVersionId; }
    public long getGenerationVersion() { return generationVersion; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
