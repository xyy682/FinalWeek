package com.finalweek.mockexam;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import com.finalweek.knowledgeversion.CourseKnowledgeVersion;
import com.finalweek.task.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

public class MockExam {
    private UUID id;
    private UUID userId;
    private UUID courseId;
    private UUID knowledgeVersionId;
    private UUID taskId;
    private UUID retryOfId;
    private String idempotencyKey;
    private String displayName;
    private String pdfTitle;
    private TaskStatus status;
    private String requestJson;
    private String requestHash;
    private String qualityPolicyVersion;
    private BigDecimal historySimilarityThreshold;
    private String pdfTemplateVersion;
    private String formulaPolicyVersion;
    private boolean allowGeneralKnowledge;
    private int questionCount;
    private int scoreSum;
    private Integer totalScoreRequested;
    private Integer durationMinutes;
    private String warningsJson;
    private String errorCode;
    private String errorMessage;
    private String paperObjectKey;
    private String answerObjectKey;
    private Instant createdAt;
    private Instant completedAt;
    private Instant deletedAt;

    protected MockExam() {}
    public MockExam(UUID userId, UUID courseId, CourseKnowledgeVersion knowledgeVersion, MockExam retryOf,
                    String idempotencyKey, String displayName, String pdfTitle, String requestJson,
                    String requestHash, MockExamRequestNormalizer.Normalized request,
                    String qualityPolicyVersion, double historySimilarityThreshold,
                    String pdfTemplateVersion, String formulaPolicyVersion) {
        this.userId = userId; this.courseId = courseId; this.knowledgeVersionId = knowledgeVersion.getId();
        this.retryOfId = retryOf == null ? null : retryOf.getId();
        this.idempotencyKey = idempotencyKey; this.displayName = displayName; this.pdfTitle = pdfTitle;
        this.requestJson = requestJson; this.requestHash = requestHash; this.status = TaskStatus.PENDING_PUBLISH;
        this.qualityPolicyVersion = qualityPolicyVersion;
        this.historySimilarityThreshold = BigDecimal.valueOf(historySimilarityThreshold);
        this.pdfTemplateVersion = pdfTemplateVersion; this.formulaPolicyVersion = formulaPolicyVersion;
        this.allowGeneralKnowledge = request.allowGeneralKnowledge(); this.questionCount = request.questionCount();
        this.scoreSum = request.scoreSum(); this.totalScoreRequested = request.totalScore();
        this.durationMinutes = request.durationMinutes(); this.warningsJson = "[]";
    }
    @BeforeInsert void created() { createdAt = Instant.now(); }
    public void attachTask(BackgroundTask value) { taskId = value.getId(); }
    public void status(TaskStatus value) { status = value; if (value.terminal()) completedAt = Instant.now(); }
    public void fail(String code, String message) { status = TaskStatus.FAILED; errorCode = code; errorMessage = message; completedAt = Instant.now(); }
    public void retrying() { status = TaskStatus.RETRYING; errorCode = null; errorMessage = null; completedAt = null; }
    public void warnings(String json) { warningsJson = json; }
    public void artifacts(String paper, String answer) { paperObjectKey = paper; answerObjectKey = answer; }
    public void delete() { deletedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getKnowledgeVersionId() { return knowledgeVersionId; }
    public UUID getTaskId() { return taskId; }
    public UUID getRetryOfId() { return retryOfId; }
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
