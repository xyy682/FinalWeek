package com.finalweek.outline;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseService;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.ParseTaskRepository;
import com.finalweek.task.TaskDispatchService;
import com.finalweek.task.TaskRateLimiter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OutlineServiceTest {
    @Test
    void rateLimitFailureDoesNotCreateTask() {
        var courses = mock(CourseService.class);
        var segments = mock(CourseSegmentRepository.class);
        var outlines = mock(OutlineRepository.class);
        var nodes = mock(OutlineNodeRepository.class);
        var tasks = mock(ParseTaskRepository.class);
        var limiter = mock(TaskRateLimiter.class);
        var factory = mock(OutlineTaskFactory.class);
        var dispatcher = mock(TaskDispatchService.class);
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        when(tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(segments.findAllRetrievable(userId, courseId)).thenReturn(List.of(mock(CourseSegment.class)));
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TASK_PUBLISH_RATE_LIMITED", "limited"))
                .when(limiter).acquirePlan(userId);
        var service = new OutlineService(courses, segments, outlines, nodes, tasks, limiter, factory,
                dispatcher, new ObjectMapper());

        assertThatThrownBy(() -> service.generate(userId, courseId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.status())
                                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verifyNoInteractions(factory, dispatcher);
    }
}
