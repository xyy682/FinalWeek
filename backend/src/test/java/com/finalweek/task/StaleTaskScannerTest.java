package com.finalweek.task;

import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaleTaskScannerTest {
    @Test
    void expiredProcessingLeaseIsRecoveredOnceAndRepublished() {
        var tasks = mock(BackgroundTaskRepository.class);
        var states = mock(TaskStateService.class);
        var dispatcher = mock(TaskDispatchService.class);
        var properties = mock(TaskProperties.class);
        var task = mock(BackgroundTask.class);
        var taskId = UUID.randomUUID();
        when(properties.stalePendingThreshold()).thenReturn(Duration.ofSeconds(30));
        when(tasks.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(TaskStatus.PENDING_PUBLISH), any(Instant.class))).thenReturn(List.of());
        when(tasks.findExpiredProcessingLeases(any(Instant.class))).thenReturn(List.of(task));
        when(task.getId()).thenReturn(taskId);
        when(task.getExecutionRound()).thenReturn(2);
        when(task.getProcessingOwner()).thenReturn("message-owner");
        when(states.recoverExpiredLease(eq(taskId), eq(2), eq("message-owner"), any(Instant.class)))
                .thenReturn(Optional.of(task));

        new StaleTaskScanner(tasks, states, dispatcher, properties).reconcile();

        verify(dispatcher).dispatchRecovered(task);
    }

    @Test
    void lostRecoveryRaceDoesNotRepublishTask() {
        var tasks = mock(BackgroundTaskRepository.class);
        var states = mock(TaskStateService.class);
        var dispatcher = mock(TaskDispatchService.class);
        var properties = mock(TaskProperties.class);
        var task = mock(BackgroundTask.class);
        when(properties.stalePendingThreshold()).thenReturn(Duration.ofSeconds(30));
        when(tasks.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(TaskStatus.PENDING_PUBLISH), any(Instant.class))).thenReturn(List.of());
        when(tasks.findExpiredProcessingLeases(any(Instant.class))).thenReturn(List.of(task));
        when(task.getId()).thenReturn(UUID.randomUUID());
        when(task.getProcessingOwner()).thenReturn("old-owner");
        when(states.recoverExpiredLease(any(), anyInt(), eq("old-owner"), any(Instant.class)))
                .thenReturn(Optional.empty());

        new StaleTaskScanner(tasks, states, dispatcher, properties).reconcile();

        verifyNoInteractions(dispatcher);
    }
}