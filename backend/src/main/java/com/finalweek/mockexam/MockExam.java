package com.finalweek.mockexam;

import com.finalweek.knowledgeversion.CourseKnowledgeVersion;
import com.finalweek.task.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "mock_exam")
public class MockExam {
    @Id @UuidGenerator private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "course_id", nullable = false) private UUID courseId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_version_id", nullable = false) private CourseKnowledgeVersion knowledgeVersion;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "task_id", unique = true) private BackgroundTask task;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "retry_of_id") private MockExam retryOf;
    @Column(name = "idempotency_key", nullable = false, length = 80) private String idempotencyKey;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(name = "pdf_title", nullable = false, length = 160) private String pdfTitle;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private TaskStatus status;
    @Column(name = "request_json", nullable = false, columnDefinition = "json") private String requestJson;
    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "char(64)") private String requestHash;
    @Column(name = "quality_policy_version", nullable = false, length = 30) private String qualityPolicyVersion;
    @Column(name = "history_similarity_threshold", nullable = false, precision = 5, scale = 4)
    private BigDecimal historySimilarityThreshold;
    @Column(name = "pdf_template_version", nullable = false, length = 30) private String pdfTemplateVersion;
    @Column(name = "formula_policy_version", nullable = false, length = 30) private String formulaPolicyVersion;
    @Column(name = "allow_general_knowledge", nullable = false) private boolean allowGeneralKnowledge;
    @Column(name = "question_count", nullable = false) private int questionCount;
    @Column(name = "score_sum", nullable = false) private int scoreSum;
    @Column(name = "total_score_requested") private Integer totalScoreRequested;
    @Column(name = "duration_minutes") private Integer durationMinutes;
    @Column(name = "warnings_json", nullable = false, columnDefinition = "json") private String warningsJson;
    @Column(name = "error_code", length = 80) private String errorCode;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "paper_object_key", length = 512) private String paperObjectKey;
    @Column(name = "answer_object_key", length = 512) private String answerObjectKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "deleted_at") private Instant deletedAt;

    protected MockExam() {}
    public MockExam(UUID userId, UUID courseId, CourseKnowledgeVersion knowledgeVersion, MockExam retryOf,
                    String idempotencyKey, String displayName, String pdfTitle, String requestJson,
                    String requestHash, MockExamRequestNormalizer.Normalized request,
                    String qualityPolicyVersion, double historySimilarityThreshold,
                    String pdfTemplateVersion, String formulaPolicyVersion) {
        this.userId = userId; this.courseId = courseId; this.knowledgeVersion = knowledgeVersion; this.retryOf = retryOf;
        this.idempotencyKey = idempotencyKey; this.displayName = displayName; this.pdfTitle = pdfTitle;
        this.requestJson = requestJson; this.requestHash = requestHash; this.status = TaskStatus.PENDING_PUBLISH;
        this.qualityPolicyVersion = qualityPolicyVersion;
        this.historySimilarityThreshold = BigDecimal.valueOf(historySimilarityThreshold);
        this.pdfTemplateVersion = pdfTemplateVersion; this.formulaPolicyVersion = formulaPolicyVersion;
        this.allowGeneralKnowledge = request.allowGeneralKnowledge(); this.questionCount = request.questionCount();
        this.scoreSum = request.scoreSum(); this.totalScoreRequested = request.totalScore();
        this.durationMinutes = request.durationMinutes(); this.warningsJson = "[]";
    }
    @PrePersist void created() { createdAt = Instant.now(); }
    public void attachTask(BackgroundTask value) { task = value; }
    public void status(TaskStatus value) { status = value; if (value.terminal()) completedAt = Instant.now(); }
    public void fail(String code, String message) { status = TaskStatus.FAILED; errorCode = code; errorMessage = message; completedAt = Instant.now(); }
    public void retrying() { status = TaskStatus.RETRYING; errorCode = null; errorMessage = null; completedAt = null; }
    public void warnings(String json) { warningsJson = json; }
    public void artifacts(String paper, String answer) { paperObjectKey = paper; answerObjectKey = answer; }
    public void delete() { deletedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getKnowledgeVersionId() { return knowledgeVersion.getId(); }
    public CourseKnowledgeVersion getKnowledgeVersion() { return knowledgeVersion; }
    public UUID getTaskId() { return task == null ? null : task.getId(); }
    public UUID getRetryOfId() { return retryOf == null ? null : retryOf.id; }
    public String getDisplayName() { return displayName; }
    public String getPdfTitle() { return pdfTitle; }
    public TaskStatus getStatus() { return status; }
    public String getRequestJson() { return requestJson; }
    public String getRequestHash() { return requestHash; }
    public String getQualityPolicyVersion() { return qualityPolicyVersion; }
    public double getHistorySimilarityThreshold() { return historySimilarityThreshold.doubleValue(); }
    public String getPdfTemplateVersion() { return pdfTemplateVersion; }
    public String getFormulaPolicyVersion() { return formulaPolicyVersion; }
    public boolean isAllowGeneralKnowledge() { return allowGeneralKnowledge; }
    public int getQuestionCount() { return questionCount; }
    public int getScoreSum() { return scoreSum; }
    public Integer getTotalScoreRequested() { return totalScoreRequested; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public String getWarningsJson() { return warningsJson; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getPaperObjectKey() { return paperObjectKey; }
    public String getAnswerObjectKey() { return answerObjectKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
