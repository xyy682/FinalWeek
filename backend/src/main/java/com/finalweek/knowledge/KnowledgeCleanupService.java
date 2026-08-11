package com.finalweek.knowledge;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeCleanupService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeCleanupService.class);
    private final QdrantVectorStore vectors;
    private final LuceneCourseIndex lucene;
    public KnowledgeCleanupService(QdrantVectorStore vectors, LuceneCourseIndex lucene) {
        this.vectors = vectors; this.lucene = lucene;
    }
    public void deleteMaterial(UUID courseId, UUID materialId) {
        try { vectors.deleteMaterial(materialId); }
        catch (RuntimeException exception) { log.warn("Qdrant material cleanup needs reconciliation materialId={}", materialId, exception); }
        try { lucene.deleteMaterial(courseId, materialId); }
        catch (RuntimeException exception) { log.warn("Lucene material cleanup needs reconciliation materialId={}", materialId, exception); }
    }
    public void deleteCourse(UUID courseId) {
        try { vectors.deleteCourse(courseId); }
        catch (RuntimeException exception) { log.warn("Qdrant course cleanup needs reconciliation courseId={}", courseId, exception); }
        try { lucene.deleteCourse(courseId); }
        catch (RuntimeException exception) { log.warn("Lucene course cleanup needs reconciliation courseId={}", courseId, exception); }
    }
}
