package com.finalweek.outline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseService;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutlineService {
    private final CourseService courses;
    private final CourseSegmentRepository segments;
    private final OutlineRepository outlines;
    private final OutlineNodeRepository nodes;
    private final ParseTaskRepository tasks;
    private final TaskRateLimiter rateLimiter;
    private final OutlineTaskFactory factory;
    private final TaskDispatchService dispatcher;
    private final ObjectMapper mapper;
    public OutlineService(CourseService courses, CourseSegmentRepository segments, OutlineRepository outlines,
                          OutlineNodeRepository nodes, ParseTaskRepository tasks, TaskRateLimiter rateLimiter,
                          OutlineTaskFactory factory, TaskDispatchService dispatcher, ObjectMapper mapper) {
        this.courses = courses; this.segments = segments; this.outlines = outlines; this.nodes = nodes;
        this.tasks = tasks; this.rateLimiter = rateLimiter; this.factory = factory;
        this.dispatcher = dispatcher; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public OutlinePage get(UUID userId, UUID courseId) {
        courses.get(userId, courseId);
        var active = active(courseId).orElse(null);
        var outline = outlines.findByCourse_Id(courseId).orElse(null);
        return new OutlinePage(outline == null ? null : view(outline), active);
    }

    public OutlineTaskFactory.CreatedTask generate(UUID userId, UUID courseId) {
        courses.get(userId, courseId);
        var active = active(courseId);
        if (active.isPresent()) return new OutlineTaskFactory.CreatedTask(active.get(), false);
        if (segments.findAllRetrievable(userId, courseId).isEmpty()) throw new BusinessException(
                HttpStatus.CONFLICT, "OUTLINE_REQUIRES_MATERIAL", "至少需要一份解析成功的课程资料");
        rateLimiter.acquire(userId);
        var result = factory.create(userId, courseId);
        if (result.created()) dispatcher.dispatchAfterRateLimit(result.task());
        return result;
    }

    @Transactional
    public NodeView adjustImportance(UUID userId, UUID nodeId, OutlineImportance importance) {
        var node = nodes.findOwned(nodeId, userId).orElseThrow(this::nodeNotFound);
        var outline = outlines.findById(node.getOutlineId()).orElseThrow(this::nodeNotFound);
        node.adjust(importance); nodes.save(node); outline.touch(); outlines.save(outline);
        return nodeView(node);
    }

    private Optional<ParseTask> active(UUID courseId) {
        return tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(
                courseId, TaskType.GENERATE_OUTLINE, OutlineTaskFactory.ACTIVE);
    }
    private OutlineView view(Outline outline) {
        var values = nodes.findAllByOutline_IdOrderByPosition(outline.getId());
        var byParent = new HashMap<UUID, List<OutlineNode>>();
        var roots = new ArrayList<OutlineNode>();
        for (var node : values) {
            if (node.getParentId() == null) roots.add(node);
            else byParent.computeIfAbsent(node.getParentId(), ignored -> new ArrayList<>()).add(node);
        }
        roots.sort(Comparator.comparingInt(OutlineNode::getPosition));
        byParent.values().forEach(list -> list.sort(Comparator.comparingInt(OutlineNode::getPosition)));
        return new OutlineView(outline.getId(), outline.getGenerationVersion(), outline.getGeneratedAt(),
                outline.getUpdatedAt(), roots.stream().map(node -> tree(node, byParent)).toList());
    }
    private NodeView tree(OutlineNode node, Map<UUID, List<OutlineNode>> children) {
        return new NodeView(node.getId(), node.getTitle(), node.getImportance(), node.isImportanceManuallyAdjusted(),
                refs(node), children.getOrDefault(node.getId(), List.of()).stream()
                        .map(child -> tree(child, children)).toList());
    }
    private NodeView nodeView(OutlineNode node) {
        return new NodeView(node.getId(), node.getTitle(), node.getImportance(),
                node.isImportanceManuallyAdjusted(), refs(node), List.of());
    }
    private List<OutlineSourceRef> refs(OutlineNode node) {
        try { return mapper.readValue(node.getSourceRefsJson(), new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("提纲来源数据损坏", exception); }
    }
    private BusinessException nodeNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "OUTLINE_NODE_NOT_FOUND", "提纲知识点不存在或无权访问");
    }

    public record OutlinePage(OutlineView outline, ParseTask activeTask) {}
    public record OutlineView(UUID id, long generationVersion, java.time.Instant generatedAt,
                              java.time.Instant updatedAt, List<NodeView> nodes) {}
    public record NodeView(UUID id, String title, OutlineImportance importance, boolean importanceManuallyAdjusted,
                           List<OutlineSourceRef> sources, List<NodeView> children) {}
}
