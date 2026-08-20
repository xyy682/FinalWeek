package com.finalweek.outline;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.knowledgeversion.*;
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
        var task = mock(BackgroundTask.class);
        var course = mock(Course.class);
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        when(task.getUserId()).thenReturn(userId);
        when(task.getCourseId()).thenReturn(courseId);
        var versionId = UUID.randomUUID();
        when(task.getGenerationVersion()).thenReturn(2L);
        when(task.getBusinessId()).thenReturn(versionId);
        when(courses.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        var versions = mock(CourseKnowledgeVersionRepository.class);
        var versionMaterials = mock(CourseKnowledgeVersionMaterialRepository.class);
        var version = mock(CourseKnowledgeVersion.class);
        when(version.getCourseId()).thenReturn(courseId);
        when(version.getStatus()).thenReturn(KnowledgeVersionStatus.FAILED);
        when(versions.findByIdForUpdate(versionId)).thenReturn(Optional.of(version));
        var publisher = new OutlinePublisher(courses, outlines, nodes, segments, new ObjectMapper(),
                versions, versionMaterials);

        assertThatThrownBy(() -> publisher.publish(task, new GeneratedOutline(List.of()), Set.of()))
                .isInstanceOfSatisfying(PermanentTaskException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("KNOWLEDGE_VERSION_STALE"));

        verifyNoInteractions(outlines, nodes, segments);
    }
}
