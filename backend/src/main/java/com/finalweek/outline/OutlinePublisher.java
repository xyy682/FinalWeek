package com.finalweek.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.course.CourseRepository;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.task.ParseTask;
import com.finalweek.task.PermanentTaskException;
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
    public OutlinePublisher(CourseRepository courses, OutlineRepository outlines, OutlineNodeRepository nodes,
                            CourseSegmentRepository segments, ObjectMapper mapper) {
        this.courses = courses; this.outlines = outlines; this.nodes = nodes; this.segments = segments; this.mapper = mapper;
    }

    @Transactional
    public void publish(ParseTask task, GeneratedOutline generated, Set<UUID> retrievedIds) {
        var course = courses.findOwnedByIdForUpdate(task.getCourseId(), task.getUserId()).orElseThrow(() ->
                new PermanentTaskException("COURSE_NOT_FOUND", "课程不存在或已删除"));
        if (task.getGenerationVersion() == null || course.getOutlineGenerationSequence() != task.getGenerationVersion()) {
            throw new PermanentTaskException("OUTLINE_STALE_GENERATION", "旧提纲生成结果不能覆盖较新版本");
        }
        var citedIds = new LinkedHashSet<UUID>(); collect(generated.nodes(), citedIds);
        if (!retrievedIds.containsAll(citedIds)) throw new PermanentTaskException(
                "OUTLINE_SOURCE_INVALID", "提纲引用不属于本次检索上下文");
        var owned = segments.findRetrievableByIds(new ArrayList<>(citedIds), task.getUserId(), task.getCourseId());
        if (owned.size() != citedIds.size()) throw new PermanentTaskException(
                "OUTLINE_SOURCE_INVALID", "提纲引用不属于当前用户或课程");
        var byId = new HashMap<UUID, CourseSegment>(); owned.forEach(value -> byId.put(value.getId(), value));

        var outline = outlines.findByCourse_Id(task.getCourseId()).orElse(null);
        if (outline == null) outline = outlines.saveAndFlush(new Outline(course, task.getGenerationVersion()));
        else {
            nodes.deleteAllForOutline(outline.getId());
            outline.publish(task.getGenerationVersion()); outlines.saveAndFlush(outline);
        }
        saveLevel(outline, null, "", generated.nodes(), byId);
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
