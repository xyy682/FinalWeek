package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.knowledgeversion.CourseKnowledgeVersionRepository;
import com.finalweek.outline.*;
import com.finalweek.task.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class MockExamCoordinatorTest {
    @Test void activeGenerationInSameCourseRejectsSecondExamBeforeCreatingRecords() {
        var courses = mock(CourseRepository.class);
        var versions = mock(CourseKnowledgeVersionRepository.class);
        var outlines = mock(OutlineRepository.class);
        var nodes = mock(OutlineNodeRepository.class);
        var exams = mock(MockExamRepository.class);
        var tasks = mock(BackgroundTaskRepository.class);
        var properties = new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300,
                2000, 10, .82, "v1", 8);
        var pdf = new MockExamPdfProperties("xelatex", Duration.ofSeconds(30), DataSize.ofMegabytes(10),
                DataSize.ofMegabytes(1), Path.of("tmp"), "v1", "v1");
        var coordinator = new MockExamCoordinator(courses, versions, outlines, nodes, exams, tasks,
                new MockExamRequestNormalizer(properties), new ObjectMapper(), properties, pdf);
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID(); var versionId = UUID.randomUUID();
        var course = mock(Course.class); var outline = mock(Outline.class); var active = mock(BackgroundTask.class);
        when(course.getCurrentKnowledgeVersionId()).thenReturn(versionId);
        when(courses.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(outlines.findByKnowledgeVersion_Id(versionId)).thenReturn(Optional.of(outline));
        when(nodes.findAllByOutline_IdOrderByPosition(any())).thenReturn(List.of());
        when(active.getTaskType()).thenReturn(TaskType.GENERATE_MOCK_EXAM);
        when(active.getStatus()).thenReturn(TaskStatus.PROCESSING);
        when(tasks.lockAllByCourseId(courseId)).thenReturn(List.of(active));
        var request = new MockExamRequestNormalizer.Request(null, MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 1), ScoreMode.AUTO, Map.of(),
                null, null, false, null);

        assertThatThrownBy(() -> coordinator.create(userId, courseId, "exam-key-123", request, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("MOCK_EXAM_GENERATION_IN_PROGRESS"));

        verify(exams, never()).saveAndFlush(any());
        verify(tasks, never()).saveAndFlush(any());
    }
}
