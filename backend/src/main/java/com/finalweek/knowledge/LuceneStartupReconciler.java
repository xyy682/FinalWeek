package com.finalweek.knowledge;

import com.finalweek.material.CourseSegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LuceneStartupReconciler implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LuceneStartupReconciler.class);
    private final CourseSegmentRepository segments;
    private final LuceneCourseIndex index;
    public LuceneStartupReconciler(CourseSegmentRepository segments, LuceneCourseIndex index) {
        this.segments = segments; this.index = index;
    }
    @Override public void run(ApplicationArguments arguments) {
        for (var courseId : segments.findCourseIdsWithRetrievableSegments()) {
            var expected = segments.findAllRetrievableByCourseId(courseId);
            if (index.count(courseId) != expected.size()) {
                log.warn("Rebuilding missing or inconsistent Lucene index courseId={} expectedSegments={}",
                        courseId, expected.size());
                index.rebuild(courseId, expected);
            }
        }
    }
}
