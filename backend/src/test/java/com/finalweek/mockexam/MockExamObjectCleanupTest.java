package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class MockExamObjectCleanupTest {
    @Test void retriesWithBackoffAndStopsAtConfiguredMaximum() {
        var cleanup = new MockExamObjectCleanup(mock(MockExam.class), "paper", "answer");
        cleanup.claim(); cleanup.retry("temporary failure", 2);
        assertThat(cleanup.getStatus()).isEqualTo(MockExamCleanupStatus.PENDING);
        assertThat(cleanup.getAttemptCount()).isEqualTo(1);
        assertThat(cleanup.getNextAttemptAt()).isNotNull();

        cleanup.claim(); cleanup.retry("still unavailable", 2);
        assertThat(cleanup.getStatus()).isEqualTo(MockExamCleanupStatus.FAILED);
        assertThat(cleanup.getErrorMessage()).isEqualTo("still unavailable");
    }

    @Test void terminalCleanupCanBeRequeuedForLaterLogicalDeletion() {
        var cleanup = new MockExamObjectCleanup(mock(MockExam.class), "partial-paper", "partial-answer");
        cleanup.claim(); cleanup.retry("storage unavailable", 1);

        cleanup.requeue(null, "final-answer");

        assertThat(cleanup.getStatus()).isEqualTo(MockExamCleanupStatus.PENDING);
        assertThat(cleanup.getAttemptCount()).isZero();
        assertThat(cleanup.getErrorMessage()).isNull();
        assertThat(cleanup.getPaperObjectKey()).isEqualTo("partial-paper");
        assertThat(cleanup.getAnswerObjectKey()).isEqualTo("final-answer");
    }
}
