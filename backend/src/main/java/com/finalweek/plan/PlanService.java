package com.finalweek.plan;

import com.finalweek.ai.LlmClient;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
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
    private final PlanGenerationValidator validator;
    private final TaskRateLimiter rateLimiter;
    private final LlmClient llm;
    private final FinalWeekProperties properties;
    public PlanService(CourseService courses, StudyPlanRepository plans, PlanTaskRepository tasks,
                       PlanRequestCoordinator coordinator, PlanGenerationValidator validator,
                       TaskRateLimiter rateLimiter, LlmClient llm, FinalWeekProperties properties) {
        this.courses = courses; this.plans = plans; this.tasks = tasks; this.coordinator = coordinator;
        this.validator = validator; this.rateLimiter = rateLimiter; this.llm = llm; this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PlanView get(UUID userId, UUID courseId) {
        courses.get(userId, courseId);
        var plan = plans.findByCourse_Id(courseId).orElse(null);
        return plan == null ? null : view(plan);
    }

    public GenerateResult generate(UUID userId, UUID courseId, String idempotencyKey,
                                   PlanRequestCoordinator.PlanInput input) {
        validateKey(idempotencyKey);
        var today = LocalDate.now(USER_ZONE);
        if (!input.examDate().isAfter(today)) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "PLAN_EXAM_DATE_INVALID", "考试日期必须晚于今天");
        var start = coordinator.start(userId, courseId, idempotencyKey, hash(input));
        if (start.replay()) return new GenerateResult(get(userId, courseId), true);
        try {
            rateLimiter.acquirePlan(userId);
            var allowed = new LinkedHashSet<UUID>(); start.nodes().forEach(node -> allowed.add(node.id()));
            var json = llm.generateJson(systemPrompt(), userPrompt(input, today, start.nodes()),
                    properties.ai().planRequestTimeout());
            var generated = validator.parse(json, allowed, today, input.examDate(), input.dailyMinutes());
            coordinator.publish(userId, courseId, start.requestId(), input, start.outline(), generated);
            return new GenerateResult(get(userId, courseId), false);
        } catch (BusinessException exception) {
            coordinator.fail(start.requestId(), exception.code());
            throw exception;
        } catch (PermanentTaskException exception) {
            coordinator.fail(start.requestId(), exception.code());
            throw new BusinessException(HttpStatus.BAD_GATEWAY, exception.code(), exception.getMessage());
        } catch (RetryableTaskException exception) {
            coordinator.fail(start.requestId(), exception.code());
            var status = exception.code().equals("LLM_TIMEOUT") ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            throw new BusinessException(status, exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            coordinator.fail(start.requestId(), "PLAN_GENERATION_FAILED");
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "PLAN_GENERATION_FAILED", "计划生成失败，请稍后重试");
        }
    }

    @Transactional
    public TaskView setCompleted(UUID userId, UUID taskId, boolean completed) {
        var task = tasks.findOwned(taskId, userId).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                "PLAN_TASK_NOT_FOUND", "计划任务不存在或无权访问"));
        task.setCompleted(completed); tasks.save(task);
        return TaskView.from(task);
    }

    private String systemPrompt() {
        return "你是严谨的复习计划生成器。必须只返回 JSON 对象，严格符合提供的字段；只能使用给定提纲节点 ID。";
    }
    private String userPrompt(PlanRequestCoordinator.PlanInput input, LocalDate today,
                              List<PlanRequestCoordinator.Node> nodes) {
        var nodeLines = new StringBuilder();
        nodes.forEach(node -> nodeLines.append(node.id()).append(" | ").append(node.importance())
                .append(" | ").append(node.title().replace("\n", " ")).append('\n'));
        return """
                请生成简单每日复习计划 JSON：
                {"tasks":[{"outlineNodeId":"UUID","plannedDate":"YYYY-MM-DD","estimatedMinutes":整数}]}
                今天：%s；考试日期：%s（任务最晚只能到考试日前一天）；每日最多：%d 分钟；
                整体掌握程度：%s；目标成绩：%d。
                高重要度与较低掌握程度应分配更多时间；每个日期的总分钟数不得超过每日上限，全部任务总分钟数不得超过可用总时间。
                只能从以下当前提纲节点选择，可按复习需要重复节点：
                %s
                """.formatted(today, input.examDate(), input.dailyMinutes(), input.masteryLevel(),
                input.targetScore(), nodeLines);
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
    private PlanView view(StudyPlan plan) {
        return new PlanView(plan.getId(), plan.getExamDate(), plan.getDailyMinutes(), plan.getMasteryLevel(),
                plan.getTargetScore(), plan.getOutlineGenerationVersion(), plan.getGeneratedAt(), plan.getVersion(),
                tasks.findAllByPlan_IdOrderByPlannedDateAscPositionAsc(plan.getId()).stream().map(TaskView::from).toList());
    }
    public record GenerateResult(PlanView plan, boolean idempotentReplay) {}
    public record PlanView(UUID id, LocalDate examDate, int dailyMinutes, MasteryLevel masteryLevel,
                           int targetScore, long outlineGenerationVersion, Instant generatedAt,
                           long version, List<TaskView> tasks) {}
    public record TaskView(UUID id, UUID outlineNodeId, String knowledgeTitle, LocalDate plannedDate,
                           int estimatedMinutes, boolean completed, Instant completedAt) {
        static TaskView from(PlanTask value) { return new TaskView(value.getId(), value.getOutlineNodeId(),
                value.getKnowledgeTitle(), value.getPlannedDate(), value.getEstimatedMinutes(),
                value.isCompleted(), value.getCompletedAt()); }
    }
}
