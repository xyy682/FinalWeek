package com.finalweek.mockexam;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity @Table(name = "mock_exam_object_cleanup")
public class MockExamObjectCleanup {
    @Id @UuidGenerator private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "mock_exam_id", nullable = false, unique = true)
    private MockExam mockExam;
    @Column(name = "paper_object_key", length = 512) private String paperObjectKey;
    @Column(name = "answer_object_key", length = 512) private String answerObjectKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private MockExamCleanupStatus status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "error_message", length = 500) private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected MockExamObjectCleanup() {}
    public MockExamObjectCleanup(MockExam exam) { mockExam = exam; paperObjectKey = exam.getPaperObjectKey();
        answerObjectKey = exam.getAnswerObjectKey(); status = MockExamCleanupStatus.PENDING; nextAttemptAt = Instant.now(); }
    public MockExamObjectCleanup(MockExam exam, String paperObjectKey, String answerObjectKey) {
        mockExam = exam; this.paperObjectKey = paperObjectKey; this.answerObjectKey = answerObjectKey;
        status = MockExamCleanupStatus.PENDING; nextAttemptAt = Instant.now();
    }
    @PrePersist void created() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void updated() { updatedAt = Instant.now(); }
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
    public MockExam getMockExam() { return mockExam; }
    public String getPaperObjectKey() { return paperObjectKey; }
    public String getAnswerObjectKey() { return answerObjectKey; }
}
