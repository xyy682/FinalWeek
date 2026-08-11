package com.finalweek.plan;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.outline.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanRequestCoordinatorTest {
    @Test
    void staleExpectedVersionCannotReplaceCurrentPlan() {
        var courses = mock(CourseRepository.class); var outlines = mock(OutlineRepository.class);
        var outlineNodes = mock(OutlineNodeRepository.class); var plans = mock(StudyPlanRepository.class);
        var planTasks = mock(PlanTaskRepository.class); var requests = mock(PlanGenerationRequestRepository.class);
        var coordinator = new PlanRequestCoordinator(courses, outlines, outlineNodes, plans, planTasks, requests);
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID(); var requestId = UUID.randomUUID();
        var course = mock(Course.class); var request = mock(PlanGenerationRequest.class);
        var outline = mock(Outline.class); var current = mock(StudyPlan.class);
        when(courses.findOwnedByIdForUpdate(courseId, userId)).thenReturn(Optional.of(course));
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(request.getStatus()).thenReturn(PlanRequestStatus.PENDING);
        when(request.getExpectedPlanVersion()).thenReturn(1L);
        when(outlines.findByCourse_Id(courseId)).thenReturn(Optional.of(outline));
        var outlineId = UUID.randomUUID(); when(outline.getId()).thenReturn(outlineId);
        when(outline.getGenerationVersion()).thenReturn(4L);
        when(plans.findByCourse_Id(courseId)).thenReturn(Optional.of(current));
        when(current.getVersion()).thenReturn(2L);

        assertThatThrownBy(() -> coordinator.publish(userId, courseId, requestId,
                new PlanRequestCoordinator.PlanInput(LocalDate.now().plusDays(5), 60, MasteryLevel.LOW, 90),
                new PlanRequestCoordinator.OutlineSnapshot(outlineId, 4), new GeneratedPlan(List.of())))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(planTasks, outlineNodes);
        verify(plans, never()).save(any());
    }
}
