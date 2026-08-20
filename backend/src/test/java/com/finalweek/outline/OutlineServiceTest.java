package com.finalweek.outline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.course.CourseService;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutlineServiceTest {
    @Test
    void getReturnsOnlyCurrentPublishedOutlineAndActiveTask() {
        var courses = mock(CourseService.class); var segments = mock(CourseSegmentRepository.class);
        var outlines = mock(OutlineRepository.class); var nodes = mock(OutlineNodeRepository.class);
        var tasks = mock(BackgroundTaskRepository.class); var active = mock(BackgroundTask.class);
        var courseId = UUID.randomUUID(); var userId = UUID.randomUUID();
        when(outlines.findCurrentByCourseId(courseId)).thenReturn(Optional.empty());
        when(tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.of(active));
        var service = new OutlineService(courses, segments, outlines, nodes, tasks, mock(TaskRateLimiter.class),
                mock(OutlineTaskFactory.class), mock(TaskDispatchService.class), new ObjectMapper());

        var page = service.get(userId, courseId);

        assertThat(page.outline()).isNull();
        assertThat(page.activeTask()).isSameAs(active);
    }
}
