package com.finalweek.plan;

import com.finalweek.course.Course;
import com.finalweek.knowledgeversion.CourseKnowledgeVersion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class StudyPlan {
    private UUID id;
    private UUID courseId;
    private UUID knowledgeVersionId;
    private LocalDate examDate;
    private int dailyMinutes;
    private MasteryLevel masteryLevel;
    private int targetScore;
    private long outlineGenerationVersion;
    private Instant generatedAt;
    private long version;
    protected StudyPlan() {}
    public StudyPlan(Course course) { this.courseId = course.getId(); }
    public void replace(LocalDate examDate, int dailyMinutes, MasteryLevel masteryLevel, int targetScore,
                        CourseKnowledgeVersion knowledgeVersion, long outlineGenerationVersion, long version) {
        this.examDate = examDate; this.dailyMinutes = dailyMinutes; this.masteryLevel = masteryLevel;
        this.knowledgeVersionId = knowledgeVersion.getId();
        this.targetScore = targetScore; this.outlineGenerationVersion = outlineGenerationVersion;
        this.version = version; this.generatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getCourseId() { return courseId; }
    public UUID getKnowledgeVersionId() { return knowledgeVersionId; }
    public LocalDate getExamDate() { return examDate; }
    public int getDailyMinutes() { return dailyMinutes; }
    public MasteryLevel getMasteryLevel() { return masteryLevel; }
    public int getTargetScore() { return targetScore; }
    public long getOutlineGenerationVersion() { return outlineGenerationVersion; }
    public Instant getGeneratedAt() { return generatedAt; }
    public long getVersion() { return version; }
}
