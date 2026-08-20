package com.finalweek.plan;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseService;
import com.finalweek.task.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
    private final CourseService courses;
    private final StudyPlanRepository plans;
    private final PlanTaskRepository tasks;
    private final PlanRequestCoordinator coordinator;
    private final TaskRateLimiter rateLimiter;
    private final TaskDispatchService dispatcher;

    public PlanService(CourseService courses, StudyPlanRepository plans, PlanTaskRepository tasks,
                       PlanRequestCoordinator coordinator, TaskRateLimiter rateLimiter,
                       TaskDispatchService dispatcher) {
        this.courses = courses; this.plans = plans; this.tasks = tasks; this.coordinator = coordinator;
        this.rateLimiter = rateLimiter; this.dispatcher = dispatcher;
    }

    @Transactional(readOnly = true)
    public PlanView get(UUID userId, UUID courseId) {
        var course = courses.get(userId, courseId);
        var plan = plans.findByCourse_Id(courseId).orElse(null);
        return plan == null ? null : view(plan, !Objects.equals(plan.getKnowledgeVersionId(),
                course.getCurrentKnowledgeVersionId()));
    }

    public GenerateResult generate(UUID userId, UUID courseId, String idempotencyKey,
                                   PlanRequestCoordinator.PlanInput input) {
        validateKey(idempotencyKey);
        if (!input.examDate().isAfter(LocalDate.now(USER_ZONE))) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "PLAN_EXAM_DATE_INVALID", "考试日期必须晚于今天");
        var start = coordinator.start(userId, courseId, idempotencyKey, hash(input), input);
        if (!start.replay()) {
            try { rateLimiter.acquirePlan(userId); }
            catch (RuntimeException exception) { dispatcher.rejectBeforePublish(start.task(), exception); throw exception; }
            dispatcher.dispatchAfterRateLimit(start.task());
        }
        return new GenerateResult(start.task().getId(), start.task().getBusinessId(),
                TaskProgressService.TaskView.from(start.task()), start.replay());
    }

    @Transactional
    public TaskView setCompleted(UUID userId, UUID taskId, boolean completed) {
        var task = tasks.findOwned(taskId, userId).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                "PLAN_TASK_NOT_FOUND", "计划任务不存在或无权访问"));
        task.setCompleted(completed); tasks.save(task);
        return TaskView.from(task);
    }

    private String hash(PlanRequestCoordinator.PlanInput input) {
        try {
            var canonical = input.examDate() + "|" + input.dailyMinutes() + "|" + input.masteryLevel()
                    + "|" + input.targetScore();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,80}")) throw new BusinessException(
                HttpStatus.BAD_REQUEST, "PLAN_IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 必须为 8–80 位安全字符");
    }
    private PlanView view(StudyPlan plan, boolean stale) {
        return new PlanView(plan.getId(), plan.getKnowledgeVersionId(), stale, plan.getExamDate(),
                plan.getDailyMinutes(), plan.getMasteryLevel(), plan.getTargetScore(),
                plan.getOutlineGenerationVersion(), plan.getGeneratedAt(), plan.getVersion(),
                tasks.findAllByPlan_IdOrderByPlannedDateAscPositionAsc(plan.getId()).stream().map(TaskView::from).toList());
    }
    public record GenerateResult(UUID taskId, UUID requestId, TaskProgressService.TaskView task,
                                 boolean idempotentReplay) {}
    public record PlanView(UUID id, UUID knowledgeVersionId, boolean stale, LocalDate examDate, int dailyMinutes,
                           MasteryLevel masteryLevel, int targetScore, long outlineGenerationVersion,
                           Instant generatedAt, long version, List<TaskView> tasks) {}
    public record TaskView(UUID id, UUID outlineNodeId, String knowledgeTitle, LocalDate plannedDate,
                           int estimatedMinutes, boolean completed, Instant completedAt) {
        static TaskView from(PlanTask value) { return new TaskView(value.getId(), value.getOutlineNodeId(),
                value.getKnowledgeTitle(), value.getPlannedDate(), value.getEstimatedMinutes(),
                value.isCompleted(), value.getCompletedAt()); }
    }
}
