package com.finalweek.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class LuceneStartupReconcilerTest {
    @Test
    void rebuildsOnlyMissingOrInconsistentSuccessfulCourseIndexes() {
        var segments = mock(CourseSegmentRepository.class);
        var index = mock(LuceneCourseIndex.class);
        var consistentCourse = UUID.randomUUID();
        var damagedCourse = UUID.randomUUID();
        var consistentSegments = List.of(mock(CourseSegment.class));
        var damagedSegments = List.of(mock(CourseSegment.class), mock(CourseSegment.class));
        when(segments.findCourseIdsWithRetrievableSegments()).thenReturn(List.of(consistentCourse, damagedCourse));
        when(segments.findAllRetrievableByCourseId(consistentCourse)).thenReturn(consistentSegments);
        when(segments.findAllRetrievableByCourseId(damagedCourse)).thenReturn(damagedSegments);
        when(index.count(consistentCourse)).thenReturn(1);
        when(index.count(damagedCourse)).thenReturn(-1);

        new LuceneStartupReconciler(segments, index).run(mock(ApplicationArguments.class));

        verify(index, never()).rebuild(consistentCourse, consistentSegments);
        verify(index).rebuild(damagedCourse, damagedSegments);
    }
}
