package com.finalweek.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finalweek.ai.EmbeddingClient;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.CourseService;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridRetrievalServiceTest {
    private final CourseService courses = mock(CourseService.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final QdrantVectorStore vectors = mock(QdrantVectorStore.class);
    private final LuceneCourseIndex lucene = mock(LuceneCourseIndex.class);
    private final CourseSegmentRepository segments = mock(CourseSegmentRepository.class);
    private HybridRetrievalService retrieval;

    @BeforeEach
    void setUp() {
        var config = new FinalWeekProperties.Retrieval(20, 20, 8, 60, 512, 64, 10, 1024,
                "http://localhost:6333", "segments", Path.of("build/lucene"));
        retrieval = new HybridRetrievalService(courses, embeddings, vectors, lucene, segments,
                new FinalWeekProperties(null, null, config, null));
    }

    @Test
    void fusesAndDeduplicatesRanksThenResolvesOnlyOwnedSucceededSegments() {
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        var vectorOnly = segment();
        var common = segment();
        var lexicalOnly = segment();
        var vectorOnlyId = vectorOnly.getId();
        var commonId = common.getId();
        var lexicalOnlyId = lexicalOnly.getId();
        var queryVector = new float[] {0.1f};
        when(embeddings.embedQuery("second law")).thenReturn(queryVector);
        when(vectors.query(userId, courseId, queryVector, 20))
                .thenReturn(List.of(vectorOnlyId, commonId));
        when(lucene.search(userId, courseId, "second law", 20))
                .thenReturn(List.of(commonId, lexicalOnlyId));
        when(segments.findRetrievableByIds(any(), any(), any()))
                .thenReturn(List.of(vectorOnly, common, lexicalOnly));

        var result = retrieval.retrieve(userId, courseId, "second law");

        assertThat(result.vectorDegraded()).isFalse();
        assertThat(result.bm25Degraded()).isFalse();
        assertThat(result.hits()).extracting(hit -> hit.segment().getId())
                .containsExactly(commonId, vectorOnlyId, lexicalOnlyId);
        assertThat(result.hits().getFirst().vectorRank()).isEqualTo(2);
        assertThat(result.hits().getFirst().bm25Rank()).isEqualTo(1);
        verify(courses).get(userId, courseId);
        verify(segments).findRetrievableByIds(any(), org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(courseId));
    }

    @Test
    void degradesToOneHealthyBranchAndFailsWhenBothBranchesFail() {
        var userId = UUID.randomUUID();
        var courseId = UUID.randomUUID();
        var lexical = segment();
        var lexicalId = lexical.getId();
        when(embeddings.embedQuery(any())).thenThrow(new IllegalStateException("embedding down"));
        when(lucene.search(userId, courseId, "query", 20)).thenReturn(List.of(lexicalId));
        when(segments.findRetrievableByIds(any(), any(), any())).thenReturn(List.of(lexical));

        var degraded = retrieval.retrieve(userId, courseId, "query");
        assertThat(degraded.vectorDegraded()).isTrue();
        assertThat(degraded.bm25Degraded()).isFalse();
        assertThat(degraded.hits()).hasSize(1);

        when(lucene.search(userId, courseId, "query", 20)).thenThrow(new IllegalStateException("lucene down"));
        assertThatThrownBy(() -> retrieval.retrieve(userId, courseId, "query"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("RETRIEVAL_UNAVAILABLE"));
    }

    private CourseSegment segment() {
        var segment = mock(CourseSegment.class);
        when(segment.getId()).thenReturn(UUID.randomUUID());
        return segment;
    }
}
