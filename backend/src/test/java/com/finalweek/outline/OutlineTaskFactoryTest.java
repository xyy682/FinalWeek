package com.finalweek.outline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.BackgroundTaskRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutlineTaskFactoryTest {
    @Test
    void returnsExistingActiveTaskWithoutAdvancingGeneration() {
        var courses = mock(CourseRepository.class); var tasks = mock(BackgroundTaskRepository.class);
        var course = mock(Course.class); var active = mock(BackgroundTask.class);
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID();
        when(courses.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.of(active));

        var result = new OutlineTaskFactory(courses, tasks).create(userId, courseId, UUID.randomUUID(), 4);

        assertThat(result.created()).isFalse();
        assertThat(result.task()).isSameAs(active);
        verify(course, never()).nextOutlineGeneration();
        verify(tasks, never()).save(any());
    }

    @Test
    void createsVersionedOutlineTaskWhenGuardIsFree() {
        var courses = mock(CourseRepository.class); var tasks = mock(BackgroundTaskRepository.class);
        var course = mock(Course.class); var userId = UUID.randomUUID(); var courseId = UUID.randomUUID();
        when(courses.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(course.nextOutlineGeneration()).thenReturn(4L);
        when(tasks.save(any(BackgroundTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = new OutlineTaskFactory(courses, tasks).create(userId, courseId, UUID.randomUUID(), 4);

        assertThat(result.created()).isTrue();
        assertThat(result.task().getGenerationVersion()).isEqualTo(4);
        assertThat(result.task().getTaskType()).isEqualTo(com.finalweek.task.TaskType.GENERATE_OUTLINE);
    }
}
