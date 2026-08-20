package com.finalweek.knowledgeversion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseKnowledgeVersionMaterialRepository extends
        JpaRepository<CourseKnowledgeVersionMaterial, CourseKnowledgeVersionMaterialId> {
    List<CourseKnowledgeVersionMaterial> findAllByKnowledgeVersionIdOrderByPosition(UUID knowledgeVersionId);
}
