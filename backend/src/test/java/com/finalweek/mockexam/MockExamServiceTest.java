package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.task.TaskDispatchService;
import com.finalweek.task.TaskStatus;
import com.finalweek.upload.ObjectStorage;
import com.finalweek.upload.StorageProperties;
import org.springframework.http.HttpStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MockExamServiceTest {
    @Test void succeededExamCannotUseFailureRetryEndpoint() {
        var coordinator = mock(MockExamCoordinator.class);
        var exam = mock(MockExam.class);
        var userId = UUID.randomUUID(); var examId = UUID.randomUUID();
        when(coordinator.owned(userId, examId)).thenReturn(exam);
        when(exam.getStatus()).thenReturn(TaskStatus.SUCCEEDED);
        var service = new MockExamService(coordinator, mock(MockExamRateLimiter.class),
                mock(TaskDispatchService.class), new ObjectMapper(),
                new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300, 2000, 10, .82, "v1", 8),
                mock(MockExamCleanupService.class), mock(ObjectStorage.class), mock(StorageProperties.class));

        assertThatThrownBy(() -> service.retry(userId, examId, "retry-key-123",
                new MockExamRequestNormalizer.Request(null, MockExamScope.WHOLE_COURSE, null, null,
                        ScoreMode.AUTO, null, null, null, true, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("MOCK_EXAM_NOT_RETRYABLE"));
        verify(coordinator).owned(userId, examId);
    }

    @Test void fileAccessStopsAtOwnershipBoundaryBeforeReadingStorage() {
        var coordinator = mock(MockExamCoordinator.class); var storage = mock(ObjectStorage.class);
        var userId = UUID.randomUUID(); var examId = UUID.randomUUID();
        when(coordinator.owned(userId, examId)).thenThrow(new BusinessException(HttpStatus.NOT_FOUND,
                "MOCK_EXAM_NOT_FOUND", "模拟卷不存在或无权访问"));
        var service = new MockExamService(coordinator, mock(MockExamRateLimiter.class),
                mock(TaskDispatchService.class), new ObjectMapper(),
                new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300, 2000, 10, .82, "v1", 8),
                mock(MockExamCleanupService.class), storage, mock(StorageProperties.class));

        assertThatThrownBy(() -> service.download(userId, examId, MockExamService.FileKind.PAPER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(storage);
    }
}
