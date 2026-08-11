package com.finalweek.outline;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.ParseTask;
import com.finalweek.task.PermanentTaskException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutlinePublisherTest {
    @Test
    void staleGenerationCannotReplaceCurrentOutline() {
        var courses = mock(CourseRepository.class);
        var outlines = mock(OutlineRepository.class);
        var nodes = mock(OutlineNodeRepository.class);
        var segments = mock(CourseSegmentRepository.class);
        var task = mock(ParseTask.class);
        var course = mock(Course.class);
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        when(task.getUserId()).thenReturn(userId);
        when(task.getCourseId()).thenReturn(courseId);
        when(task.getGenerationVersion()).thenReturn(2L);
        when(courses.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(course.getOutlineGenerationSequence()).thenReturn(3L);
        var publisher = new OutlinePublisher(courses, outlines, nodes, segments, new ObjectMapper());

        assertThatThrownBy(() -> publisher.publish(task, new GeneratedOutline(List.of()), Set.of()))
                .isInstanceOfSatisfying(PermanentTaskException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("OUTLINE_STALE_GENERATION"));

        verifyNoInteractions(outlines, nodes, segments);
    }
}
