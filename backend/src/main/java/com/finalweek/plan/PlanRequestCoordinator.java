package com.finalweek.plan;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseRepository;
import com.finalweek.outline.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanRequestCoordinator {
    private final CourseRepository courses;
    private final OutlineRepository outlines;
    private final OutlineNodeRepository outlineNodes;
    private final StudyPlanRepository plans;
    private final PlanTaskRepository planTasks;
    private final PlanGenerationRequestRepository requests;
    public PlanRequestCoordinator(CourseRepository courses, OutlineRepository outlines,
                                  OutlineNodeRepository outlineNodes, StudyPlanRepository plans,
                                  PlanTaskRepository planTasks, PlanGenerationRequestRepository requests) {
        this.courses = courses; this.outlines = outlines; this.outlineNodes = outlineNodes;
        this.plans = plans; this.planTasks = planTasks; this.requests = requests;
    }

    @Transactional
    public Start start(UUID userId, UUID courseId, String key, String requestHash) {
        courses.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::courseNotFound);
        var currentPlan = plans.findByCourse_Id(courseId).orElse(null);
        var existing = requests.findForUpdate(userId, courseId, key).orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) throw new BusinessException(HttpStatus.CONFLICT,
                    "PLAN_IDEMPOTENCY_MISMATCH", "同一幂等键不能用于不同的计划参数");
            if (existing.getStatus() == PlanRequestStatus.PENDING) throw new BusinessException(HttpStatus.CONFLICT,
                    "PLAN_GENERATION_IN_PROGRESS", "相同请求仍在生成中，请先刷新当前计划");
            if (existing.getStatus() == PlanRequestStatus.SUCCEEDED) {
                if (currentPlan != null && currentPlan.getVersion() == existing.getResultPlanVersion()) {
                    return new Start(existing.getId(), true, currentPlan.getVersion(), null, List.of());
                }
                throw new BusinessException(HttpStatus.CONFLICT, "PLAN_IDEMPOTENCY_RESULT_REPLACED",
                        "该幂等请求的结果已被后续计划覆盖，请使用新的幂等键");
            }
            existing.retry(currentPlan == null ? 0 : currentPlan.getVersion());
            requests.save(existing);
            return context(existing, courseId);
        }
        var request = new PlanGenerationRequest(userId, courseId, key, requestHash,
                currentPlan == null ? 0 : currentPlan.getVersion());
        try { request = requests.saveAndFlush(request); }
        catch (DataIntegrityViolationException exception) { throw new BusinessException(HttpStatus.CONFLICT,
                "PLAN_GENERATION_IN_PROGRESS", "相同幂等请求已被接收，请刷新后重试"); }
        return context(request, courseId);
    }

    private Start context(PlanGenerationRequest request, UUID courseId) {
        var outline = outlines.findByCourse_Id(courseId).orElseThrow(() -> new BusinessException(
                HttpStatus.CONFLICT, "PLAN_REQUIRES_OUTLINE", "请先生成课程知识提纲"));
        var nodes = outlineNodes.findAllByOutline_IdOrderByPosition(outline.getId()).stream()
                .map(node -> new Node(node.getId(), node.getTitle(), node.getImportance())).toList();
        if (nodes.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT,
                "PLAN_REQUIRES_OUTLINE", "当前课程提纲没有可规划的知识点");
        return new Start(request.getId(), false, request.getExpectedPlanVersion(),
                new OutlineSnapshot(outline.getId(), outline.getGenerationVersion()), nodes);
    }

    @Transactional
    public StudyPlan publish(UUID userId, UUID courseId, UUID requestId, PlanInput input,
                             OutlineSnapshot snapshot, GeneratedPlan generated) {
        var course = courses.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::courseNotFound);
        var request = requests.findById(requestId).orElseThrow();
        if (request.getStatus() != PlanRequestStatus.PENDING) throw new BusinessException(HttpStatus.CONFLICT,
                "PLAN_REQUEST_NOT_PENDING", "计划请求已结束");
        var outline = outlines.findByCourse_Id(courseId).orElseThrow(() -> stale("当前提纲已变化"));
        if (!outline.getId().equals(snapshot.id()) || outline.getGenerationVersion() != snapshot.version()) {
            request.fail("PLAN_OUTLINE_CHANGED"); requests.save(request); throw stale("生成期间提纲已变化，请重新生成计划");
        }
        var current = plans.findByCourse_Id(courseId).orElse(null);
        var currentVersion = current == null ? 0 : current.getVersion();
        if (currentVersion != request.getExpectedPlanVersion()) {
            request.fail("PLAN_STALE_GENERATION"); requests.save(request); throw stale("旧计划结果不能覆盖较新计划");
        }
        var titleById = new HashMap<UUID, String>();
        outlineNodes.findAllByOutline_IdOrderByPosition(outline.getId())
                .forEach(node -> titleById.put(node.getId(), node.getTitle()));
        if (!titleById.keySet().containsAll(generated.tasks().stream().map(GeneratedPlan.Task::outlineNodeId).toList())) {
            request.fail("PLAN_OUTLINE_CHANGED"); requests.save(request); throw stale("计划引用的知识点已变化");
        }
        var nextVersion = currentVersion + 1;
        var plan = current == null ? new StudyPlan(course) : current;
        if (current != null) planTasks.deleteAllForPlan(plan.getId());
        plan.replace(input.examDate(), input.dailyMinutes(), input.masteryLevel(), input.targetScore(),
                outline.getGenerationVersion(), nextVersion);
        plan = plans.saveAndFlush(plan);
        var sorted = generated.tasks().stream().sorted(Comparator.comparing(GeneratedPlan.Task::plannedDate)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            var task = sorted.get(i);
            planTasks.save(new PlanTask(plan, task.outlineNodeId(), titleById.get(task.outlineNodeId()),
                    task.plannedDate(), task.estimatedMinutes(), i));
        }
        request.succeed(nextVersion); requests.save(request);
        return plan;
    }

    @Transactional
    public void fail(UUID requestId, String code) {
        requests.findById(requestId).filter(value -> value.getStatus() == PlanRequestStatus.PENDING)
                .ifPresent(value -> { value.fail(code); requests.save(value); });
    }
    private BusinessException courseNotFound() { return new BusinessException(HttpStatus.NOT_FOUND,
            "COURSE_NOT_FOUND", "课程不存在或无权访问"); }
    private BusinessException stale(String message) { return new BusinessException(HttpStatus.CONFLICT,
            "PLAN_STALE_GENERATION", message); }
    public record Node(UUID id, String title, OutlineImportance importance) {}
    public record OutlineSnapshot(UUID id, long version) {}
    public record Start(UUID requestId, boolean replay, long planVersion,
                        OutlineSnapshot outline, List<Node> nodes) {}
    public record PlanInput(java.time.LocalDate examDate, int dailyMinutes,
                            MasteryLevel masteryLevel, int targetScore) {}
}
