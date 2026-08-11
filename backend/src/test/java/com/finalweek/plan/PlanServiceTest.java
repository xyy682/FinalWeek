package com.finalweek.plan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.finalweek.ai.LlmClient;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.CourseService;
import com.finalweek.outline.OutlineImportance;
import com.finalweek.task.TaskRateLimiter;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PlanServiceTest {
    @Test
    void rateLimitDoesNotCallModelAndMarksRequestFailed() {
        var fixture = fixture(); var requestId = UUID.randomUUID(); var nodeId = UUID.randomUUID();
        when(fixture.coordinator.start(any(), any(), anyString(), anyString())).thenReturn(new PlanRequestCoordinator.Start(
                requestId, false, 0, new PlanRequestCoordinator.OutlineSnapshot(UUID.randomUUID(), 1),
                List.of(new PlanRequestCoordinator.Node(nodeId, "重点", OutlineImportance.HIGH))));
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TASK_PUBLISH_RATE_LIMITED", "limited"))
                .when(fixture.limiter).acquirePlan(fixture.userId);

        assertThatThrownBy(() -> fixture.service.generate(fixture.userId, fixture.courseId, "request-1234", fixture.input))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verifyNoInteractions(fixture.llm);
        verify(fixture.coordinator).fail(requestId, "TASK_PUBLISH_RATE_LIMITED");
    }

    @Test
    void successfulIdempotencyReplayDoesNotCallLimiterOrModel() {
        var fixture = fixture();
        when(fixture.coordinator.start(any(), any(), anyString(), anyString()))
                .thenReturn(new PlanRequestCoordinator.Start(UUID.randomUUID(), true, 3, null, List.of()));
        var plan = mock(StudyPlan.class);
        when(plan.getVersion()).thenReturn(3L); when(plan.getExamDate()).thenReturn(fixture.input.examDate());
        when(plan.getMasteryLevel()).thenReturn(MasteryLevel.MEDIUM);
        when(fixture.plans.findByCourse_Id(fixture.courseId)).thenReturn(java.util.Optional.of(plan));
        when(fixture.tasks.findAllByPlan_IdOrderByPlannedDateAscPositionAsc(any())).thenReturn(List.of());

        var result = fixture.service.generate(fixture.userId, fixture.courseId, "request-1234", fixture.input);

        assertThat(result.idempotentReplay()).isTrue();
        verifyNoInteractions(fixture.limiter, fixture.llm);
    }

    @Test
    void synchronousTimeoutMarksRequestFailedAndReturnsGatewayTimeout() {
        var fixture = fixture(); var requestId = UUID.randomUUID(); var nodeId = UUID.randomUUID();
        when(fixture.coordinator.start(any(), any(), anyString(), anyString())).thenReturn(new PlanRequestCoordinator.Start(
                requestId, false, 0, new PlanRequestCoordinator.OutlineSnapshot(UUID.randomUUID(), 1),
                List.of(new PlanRequestCoordinator.Node(nodeId, "重点", OutlineImportance.HIGH))));
        when(fixture.llm.generateJson(anyString(), anyString(), any())).thenThrow(
                new com.finalweek.task.RetryableTaskException("LLM_TIMEOUT", "timeout"));

        assertThatThrownBy(() -> fixture.service.generate(fixture.userId, fixture.courseId, "request-1234", fixture.input))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> { assertThat(exception.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                            assertThat(exception.code()).isEqualTo("LLM_TIMEOUT"); });
        verify(fixture.coordinator).fail(requestId, "LLM_TIMEOUT");
    }

    private Fixture fixture() {
        var courses = mock(CourseService.class); var plans = mock(StudyPlanRepository.class);
        var tasks = mock(PlanTaskRepository.class); var coordinator = mock(PlanRequestCoordinator.class);
        var validator = mock(PlanGenerationValidator.class); var limiter = mock(TaskRateLimiter.class);
        var llm = mock(LlmClient.class); var properties = mock(FinalWeekProperties.class);
        var ai = mock(FinalWeekProperties.Ai.class); when(properties.ai()).thenReturn(ai);
        when(ai.planRequestTimeout()).thenReturn(java.time.Duration.ofSeconds(60));
        var service = new PlanService(courses, plans, tasks, coordinator, validator, limiter, llm, properties);
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID();
        var input = new PlanRequestCoordinator.PlanInput(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(7),
                60, MasteryLevel.MEDIUM, 85);
        return new Fixture(service, plans, tasks, coordinator, limiter, llm, userId, courseId, input);
    }
    private record Fixture(PlanService service, StudyPlanRepository plans, PlanTaskRepository tasks,
                           PlanRequestCoordinator coordinator, TaskRateLimiter limiter, LlmClient llm,
                           UUID userId, UUID courseId, PlanRequestCoordinator.PlanInput input) {}
}
