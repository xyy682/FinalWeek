package com.finalweek.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseRepository;
import com.finalweek.knowledgeversion.CourseKnowledgeVersionRepository;
import com.finalweek.outline.*;
import com.finalweek.task.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanRequestCoordinator {
    private final CourseRepository courses;
    private final CourseKnowledgeVersionRepository knowledgeVersions;
    private final OutlineRepository outlines;
    private final OutlineNodeRepository outlineNodes;
    private final StudyPlanRepository plans;
    private final PlanTaskRepository planTasks;
    private final PlanGenerationRequestRepository requests;
    private final BackgroundTaskRepository backgroundTasks;
    private final ObjectMapper mapper;

    public PlanRequestCoordinator(CourseRepository courses, CourseKnowledgeVersionRepository knowledgeVersions,
                                  OutlineRepository outlines, OutlineNodeRepository outlineNodes,
                                  StudyPlanRepository plans, PlanTaskRepository planTasks,
                                  PlanGenerationRequestRepository requests, BackgroundTaskRepository backgroundTasks,
                                  ObjectMapper mapper) {
        this.courses = courses; this.knowledgeVersions = knowledgeVersions; this.outlines = outlines;
        this.outlineNodes = outlineNodes; this.plans = plans; this.planTasks = planTasks;
        this.requests = requests; this.backgroundTasks = backgroundTasks; this.mapper = mapper;
    }

    @Transactional
    public Start start(UUID userId, UUID courseId, String key, String requestHash, PlanInput input) {
        var course = courses.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::courseNotFound);
        var currentPlan = plans.findByCourse_Id(courseId).orElse(null);
        var existing = requests.findForUpdate(userId, courseId, key).orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) throw new BusinessException(HttpStatus.CONFLICT,
                    "PLAN_IDEMPOTENCY_MISMATCH", "同一幂等键不能用于不同的计划参数");
            if (existing.getBackgroundTaskId() == null) throw new BusinessException(HttpStatus.CONFLICT,
                    "PLAN_LEGACY_REQUEST", "旧版计划请求不能作为异步任务恢复，请使用新的幂等键");
            if (existing.getStatus() == PlanRequestStatus.SUCCEEDED
                    && (currentPlan == null || currentPlan.getVersion() != existing.getResultPlanVersion()))
                throw new BusinessException(HttpStatus.CONFLICT, "PLAN_IDEMPOTENCY_RESULT_REPLACED",
                        "该幂等请求的结果已被后续计划覆盖，请使用新的幂等键");
            return new Start(backgroundTasks.findById(existing.getBackgroundTaskId()).orElseThrow(), true);
        }
        var activeStatuses = Set.of(TaskStatus.PENDING_PUBLISH, TaskStatus.PUBLISH_FAILED, TaskStatus.QUEUED,
                TaskStatus.PROCESSING, TaskStatus.RETRYING);
        if (backgroundTasks.lockAllByCourseId(courseId).stream()
                .anyMatch(task -> task.getTaskType() == TaskType.GENERATE_PLAN && activeStatuses.contains(task.getStatus())))
            throw new BusinessException(HttpStatus.CONFLICT, "PLAN_GENERATION_IN_PROGRESS",
                    "本课程已有复习计划生成任务");
        if (course.getCurrentKnowledgeVersionId() == null) throw requiresOutline();
        var version = knowledgeVersions.findById(course.getCurrentKnowledgeVersionId()).orElseThrow(this::requiresOutline);
        var outline = outlines.findByKnowledgeVersion_Id(version.getId()).orElseThrow(this::requiresOutline);
        if (outlineNodes.findAllByOutline_IdOrderByPosition(outline.getId()).isEmpty()) throw requiresOutline();
        final String json;
        try { json = mapper.writeValueAsString(input); }
        catch (Exception exception) { throw new IllegalStateException("无法保存计划输入", exception); }
        var request = new PlanGenerationRequest(userId, courseId, key, requestHash,
                currentPlan == null ? 0 : currentPlan.getVersion(), version, json);
        try { request = requests.saveAndFlush(request); }
        catch (DataIntegrityViolationException exception) { throw new BusinessException(HttpStatus.CONFLICT,
                "PLAN_GENERATION_IN_PROGRESS", "相同幂等请求已被接收，请刷新后重试"); }
        var task = backgroundTasks.saveAndFlush(new BackgroundTask(userId, courseId, TaskType.GENERATE_PLAN,
                request.getId(), true));
        request.attachTask(task); requests.saveAndFlush(request);
        return new Start(task, false);
    }

    @Transactional(readOnly = true)
    public Work work(UUID requestId) {
        var request = requests.findById(requestId).orElseThrow();
        if (request.getStatus() != PlanRequestStatus.PENDING) throw new PermanentTaskException(
                "PLAN_REQUEST_NOT_PENDING", "计划请求已结束");
        final PlanInput input;
        try { input = mapper.readValue(request.getRequestJson(), PlanInput.class); }
        catch (Exception exception) { throw new PermanentTaskException("PLAN_INPUT_INVALID", "计划输入无法恢复"); }
        var outline = outlines.findByKnowledgeVersion_Id(request.getKnowledgeVersionId())
                .orElseThrow(() -> new PermanentTaskException("PLAN_REQUIRES_OUTLINE", "知识版本没有提纲"));
        var nodes = outlineNodes.findAllByOutline_IdOrderByPosition(outline.getId()).stream()
                .map(node -> new Node(node.getId(), node.getTitle(), node.getImportance())).toList();
        if (nodes.isEmpty()) throw new PermanentTaskException("PLAN_REQUIRES_OUTLINE", "知识版本提纲没有知识点");
        return new Work(request, input, new OutlineSnapshot(outline.getId(), outline.getGenerationVersion()), nodes);
    }

    @Transactional
    public StudyPlan publish(UUID requestId, PlanInput input, GeneratedPlan generated) {
        var request = requests.findById(requestId).orElseThrow();
        var course = courses.findOwnedByIdForUpdate(request.getCourseId(), request.getUserId())
                .orElseThrow(this::courseNotFound);
        if (request.getStatus() != PlanRequestStatus.PENDING) throw new PermanentTaskException(
                "PLAN_REQUEST_NOT_PENDING", "计划请求已结束");
        if (!Objects.equals(course.getCurrentKnowledgeVersionId(), request.getKnowledgeVersionId()))
            throw stale(request, "生成期间课程知识版本已变化，请重新生成计划");
        var outline = outlines.findByKnowledgeVersion_Id(request.getKnowledgeVersionId())
                .orElseThrow(() -> stale(request, "当前知识版本提纲已变化"));
        var current = plans.findByCourse_Id(request.getCourseId()).orElse(null);
        var currentVersion = current == null ? 0 : current.getVersion();
        if (currentVersion != request.getExpectedPlanVersion())
            throw stale(request, "旧计划结果不能覆盖较新计划");
        var titleById = new HashMap<UUID, String>();
        outlineNodes.findAllByOutline_IdOrderByPosition(outline.getId())
                .forEach(node -> titleById.put(node.getId(), node.getTitle()));
        if (!titleById.keySet().containsAll(generated.tasks().stream().map(GeneratedPlan.Task::outlineNodeId).toList()))
            throw stale(request, "计划引用的知识点已变化");
        var nextVersion = currentVersion + 1;
        var plan = current == null ? new StudyPlan(course) : current;
        if (current != null) planTasks.deleteAllForPlan(plan.getId());
        var knowledgeVersion = knowledgeVersions.findById(request.getKnowledgeVersionId()).orElseThrow();
        plan.replace(input.examDate(), input.dailyMinutes(), input.masteryLevel(), input.targetScore(),
                knowledgeVersion, outline.getGenerationVersion(), nextVersion);
        plan = plans.saveAndFlush(plan);
        var sorted = generated.tasks().stream().sorted(Comparator.comparing(GeneratedPlan.Task::plannedDate)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            var value = sorted.get(i);
            planTasks.save(new PlanTask(plan, value.outlineNodeId(), titleById.get(value.outlineNodeId()),
                    value.plannedDate(), value.estimatedMinutes(), i));
        }
        request.succeed(nextVersion); requests.save(request);
        return plan;
    }

    @Transactional
    public void fail(UUID requestId, String code) {
        requests.findById(requestId).filter(value -> value.getStatus() == PlanRequestStatus.PENDING)
                .ifPresent(value -> { value.fail(code); requests.save(value); });
    }

    @Transactional
    public void retry(UUID requestId) {
        var request = requests.findById(requestId).orElseThrow();
        var current = plans.findByCourse_Id(request.getCourseId()).orElse(null);
        request.retry(current == null ? 0 : current.getVersion()); requests.save(request);
    }

    private PermanentTaskException stale(PlanGenerationRequest request, String message) {
        request.fail("PLAN_STALE_GENERATION"); requests.save(request);
        return new PermanentTaskException("PLAN_STALE_GENERATION", message);
    }
    private BusinessException courseNotFound() { return new BusinessException(HttpStatus.NOT_FOUND,
            "COURSE_NOT_FOUND", "课程不存在或无权访问"); }
    private BusinessException requiresOutline() { return new BusinessException(HttpStatus.CONFLICT,
            "PLAN_REQUIRES_OUTLINE", "请先确认资料并生成课程知识提纲"); }

    public record Node(UUID id, String title, OutlineImportance importance) {}
    public record OutlineSnapshot(UUID id, long version) {}
    public record Start(BackgroundTask task, boolean replay) {}
    public record Work(PlanGenerationRequest request, PlanInput input, OutlineSnapshot outline, List<Node> nodes) {}
    public record PlanInput(java.time.LocalDate examDate, int dailyMinutes,
                            MasteryLevel masteryLevel, int targetScore) {}
}
