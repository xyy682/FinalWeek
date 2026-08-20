package com.finalweek.knowledge;

import com.finalweek.ai.EmbeddingClient;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.material.CourseSegment;
import com.finalweek.task.BackgroundTask;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexer {
    private final EmbeddingClient embeddings;
    private final QdrantVectorStore vectors;
    private final LuceneCourseIndex lucene;
    private final int batchSize;

    public KnowledgeIndexer(EmbeddingClient embeddings, QdrantVectorStore vectors,
                            LuceneCourseIndex lucene, FinalWeekProperties properties) {
        this.embeddings = embeddings; this.vectors = vectors; this.lucene = lucene;
        this.batchSize = properties.retrieval().embeddingBatchSize();
        if (batchSize < 1 || batchSize > 10) throw new IllegalArgumentException(
                "text-embedding-v4 的 EMBEDDING_BATCH_SIZE 必须为 1–10");
    }

    public void index(BackgroundTask task, List<CourseSegment> segments) {
        for (int offset = 0; offset < segments.size(); offset += batchSize) {
            var batch = segments.subList(offset, Math.min(offset + batchSize, segments.size()));
            var values = embeddings.embedDocuments(task.getId(), batch.stream().map(CourseSegment::getContent).toList());
            var points = new ArrayList<VectorPoint>();
            for (int index = 0; index < batch.size(); index++) {
                var segment = batch.get(index);
                points.add(new VectorPoint(segment.getId(), segment.getUserId(), segment.getCourseId(),
                        segment.getMaterialId(), values.get(index)));
            }
            vectors.upsert(points);
        }
        lucene.incrementalUpsert(task.getCourseId(), task.getMaterialId(), segments);
    }
}
