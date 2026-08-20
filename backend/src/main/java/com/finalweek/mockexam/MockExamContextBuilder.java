package com.finalweek.mockexam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.knowledge.HybridRetrievalService;
import com.finalweek.knowledgeversion.KnowledgeVersionService;
import com.finalweek.material.*;
import com.finalweek.outline.OutlineSourceRef;
import com.finalweek.task.PermanentTaskException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MockExamContextBuilder {
    private static final int MAX_CONTEXT_SEGMENTS = 120;
    private final HybridRetrievalService retrieval;
    private final KnowledgeVersionService knowledgeVersions;
    private final CourseSegmentRepository segments;
    private final MaterialRepository materials;
    private final ObjectMapper mapper;
    public MockExamContextBuilder(HybridRetrievalService retrieval, KnowledgeVersionService knowledgeVersions,
                                  CourseSegmentRepository segments, MaterialRepository materials, ObjectMapper mapper) {
        this.retrieval = retrieval; this.knowledgeVersions = knowledgeVersions; this.segments = segments;
        this.materials = materials; this.mapper = mapper;
    }

    public Context build(MockExamCoordinator.Work work) {
        var materialIds = Set.copyOf(knowledgeVersions.materialIds(work.exam().getKnowledgeVersionId()));
        var segmentIds = new LinkedHashSet<UUID>();
        var nodeSegmentIds = new LinkedHashMap<UUID, List<UUID>>();
        var prioritizedNodes = work.nodes().stream()
                .sorted(Comparator.comparingInt((com.finalweek.outline.OutlineNode value) -> importance(value.getImportance()))
                        .reversed().thenComparingInt(com.finalweek.outline.OutlineNode::getPosition))
                .toList();
        for (var node : prioritizedNodes) {
            try {
                var refs = mapper.readValue(node.getSourceRefsJson(), new TypeReference<List<OutlineSourceRef>>() {});
                var scoped = new ArrayList<UUID>();
                refs.forEach(ref -> { if (segmentIds.size() < MAX_CONTEXT_SEGMENTS) {
                    segmentIds.add(ref.segmentId()); scoped.add(ref.segmentId()); } });
                nodeSegmentIds.put(node.getId(), List.copyOf(scoped));
            }
            catch (Exception exception) { throw new PermanentTaskException("MOCK_EXAM_SOURCE_INVALID", "提纲来源无法读取"); }
        }
        if (work.request().scope() == MockExamScope.WHOLE_COURSE) {
            var queries = new LinkedHashSet<String>();
            queries.add("课程重点 真题 题库 教师强调 例题 考试");
            prioritizedNodes.stream().limit(20).forEach(node -> queries.add(node.getTitle()));
            for (var query : queries) {
                var result = retrieval.retrieve(work.exam().getUserId(), work.exam().getCourseId(), query, materialIds);
                result.hits().forEach(hit -> { if (segmentIds.size() < MAX_CONTEXT_SEGMENTS)
                    segmentIds.add(hit.segment().getId()); });
            }
        }
        var found = segmentIds.isEmpty() ? List.<CourseSegment>of()
                : segments.findRetrievableByIds(new ArrayList<>(segmentIds), work.exam().getUserId(), work.exam().getCourseId());
        var allowed = found.stream().filter(value -> materialIds.contains(value.getMaterialId()))
                .collect(java.util.stream.Collectors.toMap(CourseSegment::getId, value -> value, (a, b) -> a, LinkedHashMap::new));
        if (allowed.isEmpty()) throw new PermanentTaskException("MOCK_EXAM_SOURCE_INSUFFICIENT",
                "所选知识范围没有可用课程片段");
        nodeSegmentIds.replaceAll((ignored, values) -> values.stream().filter(allowed::containsKey).toList());
        var materialTypes = new HashMap<UUID, MaterialType>();
        materials.findAllByCourse_IdAndDeletedFalse(work.exam().getCourseId()).stream()
                .filter(value -> materialIds.contains(value.getId())).forEach(value -> materialTypes.put(value.getId(), value.getMaterialType()));
        boolean pastExamSignal = allowed.values().stream().anyMatch(value -> materialTypes.get(value.getMaterialId()) == MaterialType.PAST_EXAM
                || value.getContent().matches("(?s).*(历年|真题|考试|试卷).*(选择|填空|简答|计算|论述).*"));
        boolean exampleSignal = allowed.values().stream().anyMatch(value -> value.getContent().matches("(?s).*(例题|示例|课堂练习|解：).*"));
        return new Context(List.copyOf(allowed.keySet()), Map.copyOf(nodeSegmentIds), pastExamSignal,
                exampleSignal, promptContext(allowed.values()));
    }
    private int importance(com.finalweek.outline.OutlineImportance value) {
        return switch (value) { case HIGH -> 3; case MEDIUM -> 2; case LOW -> 1; };
    }
    private String promptContext(Collection<CourseSegment> values) {
        var result = new StringBuilder();
        values.forEach(value -> result.append("segmentId=").append(value.getId()).append('\n')
                .append(value.getContent()).append("\n---\n")); return result.toString();
    }
    public record Context(List<UUID> segmentIds, Map<UUID, List<UUID>> nodeSegmentIds,
                          boolean pastExamSignal, boolean exampleSignal, String promptContext) {}
}
