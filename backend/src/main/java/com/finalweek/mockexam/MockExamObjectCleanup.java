package com.finalweek.mockexam;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import java.time.Instant;
import java.util.UUID;

public class MockExamObjectCleanup {
    private UUID id;
    private UUID mockExamId;
    private String paperObjectKey;
    private String answerObjectKey;
    private MockExamCleanupStatus status;
    private int attemptCount;
    private Instant nextAttemptAt;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    protected MockExamObjectCleanup() {}
    public MockExamObjectCleanup(MockExam exam) { mockExamId = exam.getId(); paperObjectKey = exam.getPaperObjectKey();
        answerObjectKey = exam.getAnswerObjectKey(); status = MockExamCleanupStatus.PENDING; nextAttemptAt = Instant.now(); }
    public MockExamObjectCleanup(MockExam exam, String paperObjectKey, String answerObjectKey) {
        mockExamId = exam.getId(); this.paperObjectKey = paperObjectKey; this.answerObjectKey = answerObjectKey;
        status = MockExamCleanupStatus.PENDING; nextAttemptAt = Instant.now();
    }
    @BeforeInsert void created() { createdAt = Instant.now(); updatedAt = createdAt; }
    @BeforeUpdate void updated() { updatedAt = Instant.now(); }
    public void claim() { status = MockExamCleanupStatus.PROCESSING; attemptCount++; }
    public void succeed() { status = MockExamCleanupStatus.SUCCEEDED; errorMessage = null; }
    public void requeue(String paperKey, String answerKey) {
        if (paperKey != null && !paperKey.isBlank()) paperObjectKey = paperKey;
        if (answerKey != null && !answerKey.isBlank()) answerObjectKey = answerKey;
        status = MockExamCleanupStatus.PENDING;
        attemptCount = 0;
        nextAttemptAt = Instant.now();
        errorMessage = null;
    }
    public void retry(String error, int maximum) { errorMessage = truncate(error); status = attemptCount >= maximum
            ? MockExamCleanupStatus.FAILED : MockExamCleanupStatus.PENDING;
        nextAttemptAt = Instant.now().plusSeconds(Math.min(3600, 1L << Math.min(10, attemptCount))); }
    private String truncate(String value) { if (value == null) return null; return value.substring(0, Math.min(500, value.length())); }
    public UUID getId() { return id; }
    public MockExamCleanupStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getErrorMessage() { return errorMessage; }
    public UUID getMockExamId() { return mockExamId; }
    public String getPaperObjectKey() { return paperObjectKey; }
    public String getAnswerObjectKey() { return answerObjectKey; }
}
