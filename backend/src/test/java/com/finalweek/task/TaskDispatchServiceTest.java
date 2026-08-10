package com.finalweek.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskDispatchServiceTest {
    private TaskRateLimiter limiter;
    private TaskPublisher publisher;
    private TaskStateService states;
    private TaskDispatchService service;
    private ParseTask task;
    private UUID taskId;
    private UUID userId;

    @BeforeEach void setUp() {
        limiter = mock(TaskRateLimiter.class); publisher = mock(TaskPublisher.class);
        states = mock(TaskStateService.class); service = new TaskDispatchService(limiter, publisher, states);
        task = mock(ParseTask.class); taskId = UUID.randomUUID(); userId = UUID.randomUUID();
        when(task.getId()).thenReturn(taskId); when(task.getUserId()).thenReturn(userId);
        when(task.getExecutionRound()).thenReturn(2);
    }

    @Test void confirmOnlyConditionallyAdvancesPendingTask() {
        service.dispatch(task);
        verify(limiter).acquire(userId);
        verify(publisher).publish(task);
        verify(states).queued(taskId, 2);
    }

    @Test void publishFailureLeavesRecoverableDatabaseState() {
        doThrow(new IllegalStateException("nack")).when(publisher).publish(task);
        service.dispatch(task);
        verify(states).publishFailed(taskId, 2, "MQ_PUBLISH_FAILED", "nack");
        verify(states, never()).queued(any(), anyInt());
    }

    @Test void republishRateLimitHappensBeforeStateMutation() {
        doThrow(new IllegalStateException("limited")).when(limiter).acquire(userId);
        assertThatThrownBy(() -> service.republish(userId, taskId)).isInstanceOf(IllegalStateException.class);
        verify(states, never()).prepareRepublish(any(), any());
    }
}
