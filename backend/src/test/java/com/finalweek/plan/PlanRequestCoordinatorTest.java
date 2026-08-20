package com.finalweek.plan;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.course.*;
import com.finalweek.knowledgeversion.CourseKnowledgeVersionRepository;
import com.finalweek.outline.*;
import com.finalweek.task.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlanRequestCoordinatorTest {
    @Test
    void staleExpectedVersionCannotReplaceCurrentPlan() {
        var courses = mock(CourseRepository.class); var versions = mock(CourseKnowledgeVersionRepository.class);
        var outlines = mock(OutlineRepository.class); var nodes = mock(OutlineNodeRepository.class);
        var plans = mock(StudyPlanRepository.class); var planTasks = mock(PlanTaskRepository.class);
        var requests = mock(PlanGenerationRequestRepository.class); var tasks = mock(BackgroundTaskRepository.class);
        var coordinator = new PlanRequestCoordinator(courses, versions, outlines, nodes, plans, planTasks,
                requests, tasks, new ObjectMapper().findAndRegisterModules());
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID(); var requestId = UUID.randomUUID();
        var knowledgeId = UUID.randomUUID(); var course = mock(Course.class); var request = mock(PlanGenerationRequest.class);
        var outline = mock(Outline.class); var current = mock(StudyPlan.class);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(request.getUserId()).thenReturn(userId); when(request.getCourseId()).thenReturn(courseId);
        when(request.getKnowledgeVersionId()).thenReturn(knowledgeId); when(request.getStatus()).thenReturn(PlanRequestStatus.PENDING);
        when(request.getExpectedPlanVersion()).thenReturn(1L);
        when(courses.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(course.getCurrentKnowledgeVersionId()).thenReturn(knowledgeId);
        when(outlines.findByKnowledgeVersion_Id(knowledgeId)).thenReturn(Optional.of(outline));
        when(plans.findByCourse_Id(courseId)).thenReturn(Optional.of(current)); when(current.getVersion()).thenReturn(2L);

        assertThatThrownBy(() -> coordinator.publish(requestId,
                new PlanRequestCoordinator.PlanInput(LocalDate.now().plusDays(5), 60, MasteryLevel.LOW, 90),
                new GeneratedPlan(List.of())))
                .isInstanceOf(PermanentTaskException.class);
        verify(planTasks, never()).deleteAllForPlan(any()); verify(plans, never()).save(any());
    }
}
