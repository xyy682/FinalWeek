package com.finalweek.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.finalweek.course.Course;
import com.finalweek.course.CourseRepository;
import com.finalweek.knowledge.LuceneCourseIndex;
import com.finalweek.knowledge.QdrantVectorStore;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.material.Material;
import com.finalweek.material.MaterialRepository;
import com.finalweek.upload.ObjectStorage;
import com.finalweek.mockexam.MockExamRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminCleanupServiceTest {
    @Test
    void dryRunReportsOrphansWithoutDeletingAnythingOwnedByActiveRows() {
        var courses = mock(CourseRepository.class); var materials = mock(MaterialRepository.class);
        var segments = mock(CourseSegmentRepository.class); var storage = mock(ObjectStorage.class);
        var vectors = mock(QdrantVectorStore.class); var lucene = mock(LuceneCourseIndex.class);
        var course = mock(Course.class); var material = mock(Material.class);
        var courseId = UUID.randomUUID(); var materialId = UUID.randomUUID(); var pointId = UUID.randomUUID();
        when(course.getId()).thenReturn(courseId); when(courses.findAllByDeletedFalse()).thenReturn(List.of(course));
        when(courses.findAllByDeletedTrue()).thenReturn(List.of(mock(Course.class)));
        when(material.getId()).thenReturn(materialId); when(material.getObjectKey()).thenReturn("materials/valid/original");
        when(material.getPreviewObjectKey()).thenReturn(null); when(materials.findAllActive()).thenReturn(List.of(material));
        when(segments.findAllActive()).thenReturn(List.of());
        when(storage.listKeys("materials/")).thenReturn(List.of("materials/valid/original", "materials/orphan/original"));
        when(storage.listKeys("derived/")).thenReturn(List.of());
        when(vectors.listMetadata()).thenReturn(List.of(new QdrantVectorStore.VectorMetadata(pointId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        when(lucene.indexedCourseIds()).thenReturn(Set.of(courseId, UUID.randomUUID()));

        var mockExams = mock(MockExamRepository.class); when(mockExams.findAllActiveWithFiles()).thenReturn(List.of());
        when(storage.listKeys("users/")).thenReturn(List.of());
        var result = new AdminCleanupService(courses, materials, segments, storage, vectors, lucene, mockExams).run(true);

        assertThat(result.deletedCourses()).isOne(); assertThat(result.orphanMinioObjects()).isOne();
        assertThat(result.orphanQdrantPoints()).isOne(); assertThat(result.orphanLuceneCourses()).isOne();
        verify(storage, never()).delete(anyString()); verify(vectors, never()).deletePoints(anyList());
        verify(lucene, never()).deleteCourse(any());
    }
}
