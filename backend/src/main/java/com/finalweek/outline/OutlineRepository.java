package com.finalweek.outline;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutlineRepository extends JpaRepository<Outline, UUID> {
    Optional<Outline> findByKnowledgeVersion_Id(UUID knowledgeVersionId);

    @org.springframework.data.jpa.repository.Query("select outline from Outline outline " +
            "where outline.course.id = :courseId and outline.knowledgeVersion.status = 'PUBLISHED'")
    Optional<Outline> findCurrentByCourseId(
            @org.springframework.data.repository.query.Param("courseId") UUID courseId);
}
