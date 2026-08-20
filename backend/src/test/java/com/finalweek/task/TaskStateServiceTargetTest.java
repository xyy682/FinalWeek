package com.finalweek.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.finalweek.chat.ChatCoordinator;
import com.finalweek.course.CourseRepository;
import com.finalweek.knowledgeversion.CourseKnowledgeVersionRepository;
import com.finalweek.material.MaterialRepository;
import com.finalweek.mockexam.MockExamCoordinator;
import com.finalweek.plan.PlanRequestCoordinator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskStateServiceTargetTest {
    @Test void publishFailedTasksRemainRecoverableInGlobalDrawer() {
        var tasks = mock(BackgroundTaskRepository.class);
        when(tasks.findAllByUserIdAndVisibleInGlobalDrawerTrueAndStatusInOrderByUpdatedAtDesc(any(), anyList()))
                .thenReturn(List.of());
        var service = new TaskStateService(tasks, mock(MaterialRepository.class), mock(FailedTaskRepository.class),
                mock(TaskCheckpointRepository.class), mock(TaskProgressService.class),
                mock(CourseKnowledgeVersionRepository.class), mock(CourseRepository.class),
                mock(PlanRequestCoordinator.class), mock(ChatCoordinator.class), mock(MockExamCoordinator.class));

        service.active(UUID.randomUUID());

        @SuppressWarnings("unchecked")
        var statuses = ArgumentCaptor.forClass(List.class);
        verify(tasks).findAllByUserIdAndVisibleInGlobalDrawerTrueAndStatusInOrderByUpdatedAtDesc(any(), statuses.capture());
        assertThat(statuses.getValue()).contains(TaskStatus.PUBLISH_FAILED);
    }
}
