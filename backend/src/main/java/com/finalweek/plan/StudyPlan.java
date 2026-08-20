package com.finalweek.plan;

import com.finalweek.course.Course;
import com.finalweek.knowledgeversion.CourseKnowledgeVersion;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "study_plan")
public class StudyPlan {
    @Id @UuidGenerator private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "course_id", nullable = false, unique = true)
    private Course course;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_version_id", nullable = false)
    private CourseKnowledgeVersion knowledgeVersion;
    @Column(name = "exam_date", nullable = false) private LocalDate examDate;
    @Column(name = "daily_minutes", nullable = false) private int dailyMinutes;
    @Enumerated(EnumType.STRING) @Column(name = "mastery_level", nullable = false, length = 10)
    private MasteryLevel masteryLevel;
    @Column(name = "target_score", nullable = false) private int targetScore;
    @Column(name = "outline_generation_version", nullable = false) private long outlineGenerationVersion;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;
    @Column(nullable = false) private long version;
    protected StudyPlan() {}
    public StudyPlan(Course course) { this.course = course; }
    public void replace(LocalDate examDate, int dailyMinutes, MasteryLevel masteryLevel, int targetScore,
                        CourseKnowledgeVersion knowledgeVersion, long outlineGenerationVersion, long version) {
        this.examDate = examDate; this.dailyMinutes = dailyMinutes; this.masteryLevel = masteryLevel;
        this.knowledgeVersion = knowledgeVersion;
        this.targetScore = targetScore; this.outlineGenerationVersion = outlineGenerationVersion;
        this.version = version; this.generatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getCourseId() { return course.getId(); }
    public UUID getKnowledgeVersionId() { return knowledgeVersion.getId(); }
    public LocalDate getExamDate() { return examDate; }
    public int getDailyMinutes() { return dailyMinutes; }
    public MasteryLevel getMasteryLevel() { return masteryLevel; }
    public int getTargetScore() { return targetScore; }
    public long getOutlineGenerationVersion() { return outlineGenerationVersion; }
    public Instant getGeneratedAt() { return generatedAt; }
    public long getVersion() { return version; }
}
