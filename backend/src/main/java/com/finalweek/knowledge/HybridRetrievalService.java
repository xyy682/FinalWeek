package com.finalweek.knowledge;

import com.finalweek.ai.EmbeddingClient;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.CourseService;
import com.finalweek.material.CourseSegment;
import com.finalweek.material.CourseSegmentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class HybridRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);
    private final CourseService courses;
    private final EmbeddingClient embeddings;
    private final QdrantVectorStore vectors;
    private final LuceneCourseIndex lucene;
    private final CourseSegmentRepository segments;
    private final FinalWeekProperties.Retrieval config;

    public HybridRetrievalService(CourseService courses, EmbeddingClient embeddings, QdrantVectorStore vectors,
                                  LuceneCourseIndex lucene, CourseSegmentRepository segments,
                                  FinalWeekProperties properties) {
        this.courses = courses; this.embeddings = embeddings; this.vectors = vectors;
        this.lucene = lucene; this.segments = segments; this.config = properties.retrieval();
    }

    public HybridRetrievalResult retrieve(UUID userId, UUID courseId, String query) {
        return retrieve(userId, courseId, query, Set.of());
    }

    public HybridRetrievalResult retrieve(UUID userId, UUID courseId, String query, Set<UUID> materialIds) {
        courses.get(userId, courseId);
        if (query == null || query.isBlank()) throw new BusinessException(
                HttpStatus.BAD_REQUEST, "QUERY_REQUIRED", "检索问题不能为空");
        List<UUID> vectorIds = List.of(), bm25Ids = List.of();
        boolean vectorDegraded = false, bm25Degraded = false;
        try { vectorIds = materialIds.isEmpty()
                ? vectors.query(userId, courseId, embeddings.embedQuery(query), config.vectorTopK())
                : vectors.query(userId, courseId, materialIds, embeddings.embedQuery(query), config.vectorTopK()); }
        catch (RuntimeException exception) {
            vectorDegraded = true; log.warn("Vector retrieval degraded userId={} courseId={}", userId, courseId, exception);
        }
        try { bm25Ids = materialIds.isEmpty() ? lucene.search(userId, courseId, query, config.bm25TopK())
                : lucene.search(userId, courseId, materialIds, query, config.bm25TopK()); }
        catch (RuntimeException exception) {
            bm25Degraded = true; log.warn("BM25 retrieval degraded userId={} courseId={}", userId, courseId, exception);
        }
        if (vectorDegraded && bm25Degraded) throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "RETRIEVAL_UNAVAILABLE", "课程检索暂时不可用");

        var scores = new HashMap<UUID, Score>();
        add(scores, vectorIds, true); add(scores, bm25Ids, false);
        if (scores.isEmpty()) return new HybridRetrievalResult(List.of(), vectorDegraded, bm25Degraded);
        var owned = segments.findRetrievableByIds(new ArrayList<>(scores.keySet()), userId, courseId);
        var byId = new HashMap<UUID, CourseSegment>(); owned.forEach(segment -> byId.put(segment.getId(), segment));
        var hits = scores.entrySet().stream().filter(entry -> byId.containsKey(entry.getKey()))
                .filter(entry -> materialIds.isEmpty() || materialIds.contains(byId.get(entry.getKey()).getMaterialId()))
                .sorted((left, right) -> Double.compare(right.getValue().value, left.getValue().value))
                .limit(config.finalTopK()).map(entry -> {
                    var score = entry.getValue();
                    return new RetrievalHit(byId.get(entry.getKey()), score.value, score.vectorRank, score.bm25Rank);
                }).toList();
        return new HybridRetrievalResult(hits, vectorDegraded, bm25Degraded);
    }

    private void add(HashMap<UUID, Score> scores, List<UUID> ids, boolean vector) {
        for (int index = 0; index < ids.size(); index++) {
            int rank = index + 1; var score = scores.computeIfAbsent(ids.get(index), ignored -> new Score());
            score.value += 1.0 / (config.rrfK() + rank);
            if (vector) score.vectorRank = rank; else score.bm25Rank = rank;
        }
    }
    private static final class Score { double value; Integer vectorRank; Integer bm25Rank; }
}
