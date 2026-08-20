package com.finalweek.plan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseService;
import com.finalweek.task.*;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PlanServiceTest {
    @Test
    void newRequestReturnsTaskAndDispatchesWithoutCallingModelInHttpThread() {
        var fixture = fixture(); var task = task(fixture);
        when(fixture.coordinator.start(any(), any(), anyString(), anyString(), eq(fixture.input)))
                .thenReturn(new PlanRequestCoordinator.Start(task, false));

        var result = fixture.service.generate(fixture.userId, fixture.courseId, "request-1234", fixture.input);

        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.task().type()).isEqualTo(TaskType.GENERATE_PLAN);
        verify(fixture.limiter).acquirePlan(fixture.userId);
        verify(fixture.dispatcher).dispatchAfterRateLimit(task);
    }

    @Test
    void idempotencyReplayReturnsSameTaskWithoutRateLimitOrRepublish() {
        var fixture = fixture(); var task = task(fixture);
        when(fixture.coordinator.start(any(), any(), anyString(), anyString(), eq(fixture.input)))
                .thenReturn(new PlanRequestCoordinator.Start(task, true));

        var result = fixture.service.generate(fixture.userId, fixture.courseId, "request-1234", fixture.input);

        assertThat(result.idempotentReplay()).isTrue();
        verifyNoInteractions(fixture.limiter, fixture.dispatcher);
    }

    @Test
    void invalidExamDateIsRejectedBeforeCreatingTask() {
        var fixture = fixture(); var invalid = new PlanRequestCoordinator.PlanInput(LocalDate.now(), 60,
                MasteryLevel.MEDIUM, 85);
        assertThatThrownBy(() -> fixture.service.generate(fixture.userId, fixture.courseId, "request-1234", invalid))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(fixture.coordinator);
    }

    private BackgroundTask task(Fixture fixture) {
        return new BackgroundTask(fixture.userId, fixture.courseId, TaskType.GENERATE_PLAN, UUID.randomUUID(), true);
    }
    private Fixture fixture() {
        var courses = mock(CourseService.class); var plans = mock(StudyPlanRepository.class);
        var tasks = mock(PlanTaskRepository.class); var coordinator = mock(PlanRequestCoordinator.class);
        var limiter = mock(TaskRateLimiter.class); var dispatcher = mock(TaskDispatchService.class);
        var service = new PlanService(courses, plans, tasks, coordinator, limiter, dispatcher);
        var input = new PlanRequestCoordinator.PlanInput(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(7),
                60, MasteryLevel.MEDIUM, 85);
        return new Fixture(service, coordinator, limiter, dispatcher, UUID.randomUUID(), UUID.randomUUID(), input);
    }
    private record Fixture(PlanService service, PlanRequestCoordinator coordinator, TaskRateLimiter limiter,
                           TaskDispatchService dispatcher, UUID userId, UUID courseId,
                           PlanRequestCoordinator.PlanInput input) {}
}
