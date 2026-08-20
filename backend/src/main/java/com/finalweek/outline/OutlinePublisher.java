package com.finalweek.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.course.CourseRepository;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.knowledgeversion.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutlinePublisher {
    private final CourseRepository courses;
    private final OutlineRepository outlines;
    private final OutlineNodeRepository nodes;
    private final CourseSegmentRepository segments;
    private final ObjectMapper mapper;
    private final CourseKnowledgeVersionRepository versions;
    private final CourseKnowledgeVersionMaterialRepository versionMaterials;
    public OutlinePublisher(CourseRepository courses, OutlineRepository outlines, OutlineNodeRepository nodes,
                            CourseSegmentRepository segments, ObjectMapper mapper,
                            CourseKnowledgeVersionRepository versions,
                            CourseKnowledgeVersionMaterialRepository versionMaterials) {
        this.courses = courses; this.outlines = outlines; this.nodes = nodes; this.segments = segments;
        this.mapper = mapper; this.versions = versions; this.versionMaterials = versionMaterials;
    }

    @Transactional
    public void publish(BackgroundTask task, GeneratedOutline generated, Set<UUID> retrievedIds) {
        var course = courses.findOwnedByIdForUpdate(task.getCourseId(), task.getUserId()).orElseThrow(() ->
                new PermanentTaskException("COURSE_NOT_FOUND", "课程不存在或已删除"));
        var version = versions.findByIdForUpdate(task.getBusinessId()).orElseThrow(() ->
                new PermanentTaskException("KNOWLEDGE_VERSION_STALE", "课程知识版本不存在"));
        if (!version.getCourseId().equals(task.getCourseId())) throw new PermanentTaskException(
                "KNOWLEDGE_VERSION_STALE", "课程知识版本不属于当前课程");
        if (version.getStatus() == KnowledgeVersionStatus.PUBLISHED
                && version.getId().equals(course.getCurrentKnowledgeVersionId())) return;
        if (version.getStatus() != KnowledgeVersionStatus.GENERATING) throw new PermanentTaskException(
                "KNOWLEDGE_VERSION_STALE", "课程知识版本已不允许发布");
        var citedIds = new LinkedHashSet<UUID>(); collect(generated.nodes(), citedIds);
        if (!retrievedIds.containsAll(citedIds)) throw new PermanentTaskException(
                "OUTLINE_SOURCE_INVALID", "提纲引用不属于本次检索上下文");
        var owned = segments.findRetrievableByIds(new ArrayList<>(citedIds), task.getUserId(), task.getCourseId());
        if (owned.size() != citedIds.size()) throw new PermanentTaskException(
                "OUTLINE_SOURCE_INVALID", "提纲引用不属于当前用户或课程");
        var byId = new HashMap<UUID, CourseSegment>(); owned.forEach(value -> byId.put(value.getId(), value));
        var allowedMaterials = versionMaterials.findAllByKnowledgeVersionIdOrderByPosition(version.getId()).stream()
                .map(CourseKnowledgeVersionMaterial::getMaterialId).collect(java.util.stream.Collectors.toSet());
        if (owned.stream().anyMatch(segment -> !allowedMaterials.contains(segment.getMaterialId())))
            throw new PermanentTaskException("OUTLINE_SOURCE_INVALID", "提纲引用不属于课程知识版本资料快照");

        var outline = outlines.saveAndFlush(new Outline(course, version, task.getGenerationVersion()));
        saveLevel(outline, null, "", generated.nodes(), byId);
        if (course.getCurrentKnowledgeVersionId() != null) versions.findByIdForUpdate(
                course.getCurrentKnowledgeVersionId()).ifPresent(current -> {
                    if (current.getStatus() == KnowledgeVersionStatus.PUBLISHED) {
                        current.supersede();
                        versions.save(current);
                    }
                });
        version.publish(outline.getId());
        course.publishKnowledgeVersion(version.getId());
        versions.save(version); courses.save(course);
    }

    private void saveLevel(Outline outline, UUID parentId, String parentPath, List<GeneratedOutline.Node> values,
                           Map<UUID, CourseSegment> segments) {
        for (int position = 0; position < values.size(); position++) {
            var value = values.get(position); var path = parentPath.isEmpty() ? Integer.toString(position)
                    : parentPath + "." + position;
            var refs = value.sourceSegmentIds().stream().map(segments::get).map(OutlineSourceRef::from).toList();
            try {
                var entity = nodes.saveAndFlush(new OutlineNode(outline, parentId, path, value.title().strip(),
                        value.importance(), position, mapper.writeValueAsString(refs)));
                saveLevel(outline, entity.getId(), path, value.children(), segments);
            } catch (Exception exception) {
                throw new PermanentTaskException("OUTLINE_PERSIST_FAILED", "提纲无法原子保存");
            }
        }
    }
    private void collect(List<GeneratedOutline.Node> values, Set<UUID> ids) {
        for (var value : values) { ids.addAll(value.sourceSegmentIds()); collect(value.children(), ids); }
    }
}
