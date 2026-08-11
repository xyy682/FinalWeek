package com.finalweek.admin;

import com.finalweek.course.CourseRepository;
import com.finalweek.knowledge.LuceneCourseIndex;
import com.finalweek.knowledge.QdrantVectorStore;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.material.MaterialRepository;
import com.finalweek.upload.ObjectStorage;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCleanupService {
    private final CourseRepository courses; private final MaterialRepository materials;
    private final CourseSegmentRepository segments; private final ObjectStorage storage;
    private final QdrantVectorStore vectors; private final LuceneCourseIndex lucene;
    public AdminCleanupService(CourseRepository courses, MaterialRepository materials,
                               CourseSegmentRepository segments, ObjectStorage storage,
                               QdrantVectorStore vectors, LuceneCourseIndex lucene) {
        this.courses = courses; this.materials = materials; this.segments = segments;
        this.storage = storage; this.vectors = vectors; this.lucene = lucene;
    }

    @Transactional(readOnly = true)
    public CleanupStats run(boolean dryRun) {
        var activeCourses = new HashSet<UUID>(); courses.findAllByDeletedFalse().forEach(value -> activeCourses.add(value.getId()));
        var deletedCourses = courses.findAllByDeletedTrue().stream().map(value -> value.getId()).toList();
        var activeMaterials = materials.findAllActive();
        var validObjects = new HashSet<String>();
        activeMaterials.forEach(value -> { validObjects.add(value.getObjectKey());
            if (value.getPreviewObjectKey() != null) validObjects.add(value.getPreviewObjectKey());
            validObjects.add("derived/" + value.getId() + "/content-extracted.json"); });
        var orphanObjects = new ArrayList<String>();
        for (var key : concat(storage.listKeys("materials/"), storage.listKeys("derived/"))) {
            if (!validObjects.contains(key)) orphanObjects.add(key);
        }

        var activeSegments = segments.findAllActive();
        var validSegmentIds = new HashSet<UUID>(); activeSegments.forEach(value -> validSegmentIds.add(value.getId()));
        var orphanPoints = vectors.listMetadata().stream().filter(value -> !validSegmentIds.contains(value.segmentId()))
                .map(QdrantVectorStore.VectorMetadata::pointId).toList();
        var indexedCourses = lucene.indexedCourseIds();
        var orphanLuceneCourses = indexedCourses.stream().filter(id -> !activeCourses.contains(id)).toList();
        int orphanLuceneDocuments = 0; var inconsistentActiveCourses = new ArrayList<UUID>();
        var byCourse = new HashMap<UUID, List<CourseSegment>>();
        activeSegments.forEach(value -> byCourse.computeIfAbsent(value.getCourseId(), ignored -> new ArrayList<>()).add(value));
        for (var entry : byCourse.entrySet()) {
            var valid = entry.getValue().stream().map(CourseSegment::getId).collect(java.util.stream.Collectors.toSet());
            var indexed = lucene.segmentIds(entry.getKey());
            var orphan = new HashSet<>(indexed); orphan.removeAll(valid); orphanLuceneDocuments += orphan.size();
            if (!orphan.isEmpty()) inconsistentActiveCourses.add(entry.getKey());
        }
        if (!dryRun) {
            orphanObjects.forEach(storage::delete); vectors.deletePoints(orphanPoints);
            orphanLuceneCourses.forEach(lucene::deleteCourse);
            inconsistentActiveCourses.forEach(id -> lucene.rebuild(id, byCourse.getOrDefault(id, List.of())));
        }
        return new CleanupStats(dryRun, deletedCourses.size(), orphanObjects.size(), orphanPoints.size(),
                orphanLuceneCourses.size(), orphanLuceneDocuments, inconsistentActiveCourses.size());
    }

    private List<String> concat(List<String> first, List<String> second) { var values = new ArrayList<>(first); values.addAll(second); return values; }
    public record CleanupStats(boolean dryRun, int deletedCourses, int orphanMinioObjects, int orphanQdrantPoints,
                               int orphanLuceneCourses, int orphanLuceneDocuments, int rebuiltActiveCourses) {}
}
