package com.finalweek.mockexam;

import static org.mockito.Mockito.*;

import com.finalweek.upload.ObjectStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class MockExamCleanupServiceTest {
    @Test void courseDeletionHidesExamsQueuesObjectsAndDetachesSegmentSources() {
        var cleanups = mock(MockExamObjectCleanupRepository.class);
        var exams = mock(MockExamRepository.class);
        var sources = mock(MockExamQuestionSourceRepository.class);
        var exam = mock(MockExam.class);
        var examId = UUID.randomUUID(); var courseId = UUID.randomUUID();
        when(exam.getId()).thenReturn(examId);
        when(exams.findAllByCourseId(courseId)).thenReturn(List.of(exam));
        when(cleanups.findByMockExamIdForUpdate(examId)).thenReturn(java.util.Optional.empty());
        var properties = new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300,
                2000, 10, .82, "v1", 8);
        var service = new MockExamCleanupService(cleanups, mock(ObjectStorage.class), exams, sources,
                mock(PlatformTransactionManager.class), properties);

        service.prepareCourseDeletion(courseId);

        verify(exam).delete();
        verify(exams).save(exam);
        verify(cleanups).save(any(MockExamObjectCleanup.class));
        verify(sources).deleteAllByCourseId(courseId);
    }

    @Test void deletingExamRequeuesACompletedPartialObjectCleanup() {
        var cleanups = mock(MockExamObjectCleanupRepository.class);
        var exams = mock(MockExamRepository.class);
        var cleanup = mock(MockExamObjectCleanup.class);
        var exam = mock(MockExam.class);
        var examId = UUID.randomUUID();
        when(exam.getId()).thenReturn(examId);
        when(exam.getPaperObjectKey()).thenReturn("final-paper");
        when(exam.getAnswerObjectKey()).thenReturn("final-answer");
        when(cleanups.findByMockExamIdForUpdate(examId)).thenReturn(java.util.Optional.of(cleanup));
        when(cleanup.getStatus()).thenReturn(MockExamCleanupStatus.SUCCEEDED);
        var properties = new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300,
                2000, 10, .82, "v1", 8);
        var service = new MockExamCleanupService(cleanups, mock(ObjectStorage.class), exams,
                mock(MockExamQuestionSourceRepository.class), mock(PlatformTransactionManager.class), properties);

        service.enqueue(exam);

        verify(cleanup).requeue("final-paper", "final-answer");
        verify(cleanups).save(cleanup);
    }

    @Test void deletingExamLeavesAnInFlightCleanupClaimIntact() {
        var cleanups = mock(MockExamObjectCleanupRepository.class);
        var cleanup = mock(MockExamObjectCleanup.class);
        var exam = mock(MockExam.class);
        var examId = UUID.randomUUID();
        when(exam.getId()).thenReturn(examId);
        when(cleanups.findByMockExamIdForUpdate(examId)).thenReturn(java.util.Optional.of(cleanup));
        when(cleanup.getStatus()).thenReturn(MockExamCleanupStatus.PROCESSING);
        var properties = new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300,
                2000, 10, .82, "v1", 8);
        var service = new MockExamCleanupService(cleanups, mock(ObjectStorage.class), mock(MockExamRepository.class),
                mock(MockExamQuestionSourceRepository.class), mock(PlatformTransactionManager.class), properties);

        service.enqueue(exam);

        verify(cleanup, never()).requeue(any(), any());
        verify(cleanups, never()).save(cleanup);
    }

    @Test void completedObjectCleanupHardDeletesLogicallyDeletedExamAndItsCascadingQuestions() {
        var cleanups = mock(MockExamObjectCleanupRepository.class);
        var exams = mock(MockExamRepository.class);
        var cleanup = mock(MockExamObjectCleanup.class);
        var exam = mock(MockExam.class);
        var cleanupId = UUID.randomUUID(); var examId = UUID.randomUUID();
        when(cleanups.findById(cleanupId)).thenReturn(java.util.Optional.of(cleanup));
        when(cleanup.getMockExam()).thenReturn(exam);
        when(exam.getId()).thenReturn(examId);
        when(exam.getDeletedAt()).thenReturn(Instant.now());
        var properties = new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300,
                2000, 10, .82, "v1", 8);
        var service = new MockExamCleanupService(cleanups, mock(ObjectStorage.class), exams,
                mock(MockExamQuestionSourceRepository.class), mock(PlatformTransactionManager.class), properties);

        service.complete(cleanupId);

        verify(exams).detachRetriesOf(examId);
        verify(cleanups).delete(cleanup);
        verify(exams).delete(exam);
        verify(cleanup, never()).succeed();
    }

    @Test void completedPartialArtifactCleanupKeepsVisibleFailureHistory() {
        var cleanups = mock(MockExamObjectCleanupRepository.class);
        var exams = mock(MockExamRepository.class);
        var cleanup = mock(MockExamObjectCleanup.class);
        var exam = mock(MockExam.class);
        var cleanupId = UUID.randomUUID();
        when(cleanups.findById(cleanupId)).thenReturn(java.util.Optional.of(cleanup));
        when(cleanup.getMockExam()).thenReturn(exam);
        when(exam.getDeletedAt()).thenReturn(null);
        var properties = new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300,
                2000, 10, .82, "v1", 8);
        var service = new MockExamCleanupService(cleanups, mock(ObjectStorage.class), exams,
                mock(MockExamQuestionSourceRepository.class), mock(PlatformTransactionManager.class), properties);

        service.complete(cleanupId);

        verify(cleanup).succeed();
        verify(cleanups).save(cleanup);
        verify(exams, never()).delete(any());
    }
}
