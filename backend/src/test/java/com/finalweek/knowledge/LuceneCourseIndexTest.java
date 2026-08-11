package com.finalweek.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.ExtractedUnit;
import com.finalweek.material.Material;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class LuceneCourseIndexTest {
    @TempDir Path directory;

    @Test
    void serializesSameCourseWritesAndKeepsIncrementalDocumentsIdempotent() throws Exception {
        var index = index();
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        var materialA = UUID.randomUUID();
        var materialB = UUID.randomUUID();
        var first = segment(userId, courseId, materialA, 0, "relativity tensor");
        var second = segment(userId, courseId, materialB, 0, "quantum oscillator");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var writeA = executor.submit(() -> index.incrementalUpsert(courseId, materialA, List.of(first)));
            var writeB = executor.submit(() -> index.incrementalUpsert(courseId, materialB, List.of(second)));
            writeA.get();
            writeB.get();
        }

        index.incrementalUpsert(courseId, materialA, List.of(first));
        assertThat(index.count(courseId)).isEqualTo(2);
        assertThat(index.search(userId, courseId, "relativity", 10)).containsExactly(first.getId());
        assertThat(index.search(userId, courseId, "quantum", 10)).containsExactly(second.getId());
        assertThat(index.search(UUID.randomUUID(), courseId, "quantum", 10)).isEmpty();
    }

    @Test
    void rebuildValidatesAndSwitchesWholeCourseIndex() {
        var index = index();
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        var oldMaterial = UUID.randomUUID();
        var newMaterial = UUID.randomUUID();
        var oldSegment = segment(userId, courseId, oldMaterial, 0, "obsolete keyword");
        var newSegment = segment(userId, courseId, newMaterial, 0, "replacement keyword");
        index.incrementalUpsert(courseId, oldMaterial, List.of(oldSegment));

        index.rebuild(courseId, List.of(newSegment));

        assertThat(index.count(courseId)).isOne();
        assertThat(index.search(userId, courseId, "obsolete", 10)).isEmpty();
        assertThat(index.search(userId, courseId, "replacement", 10)).containsExactly(newSegment.getId());
    }

    private LuceneCourseIndex index() {
        var redisson = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        var retrieval = new FinalWeekProperties.Retrieval(20, 20, 8, 60, 512, 64, 10, 1024,
                "http://localhost:6333", "segments", directory);
        return new LuceneCourseIndex(new FinalWeekProperties(null, null, retrieval, null), redisson);
    }

    private CourseSegment segment(UUID userId, UUID courseId, UUID materialId, int chunk, String content) {
        var material = mock(Material.class);
        when(material.getId()).thenReturn(materialId);
        return new CourseSegment(userId, courseId, material, chunk, 2, ExtractedUnit.paragraph(chunk + 1, content));
    }
}
