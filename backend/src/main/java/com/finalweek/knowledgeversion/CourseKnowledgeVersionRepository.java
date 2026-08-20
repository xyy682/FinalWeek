package com.finalweek.knowledgeversion;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseKnowledgeVersionRepository extends JpaRepository<CourseKnowledgeVersion, UUID> {
    Optional<CourseKnowledgeVersion> findByCourse_IdAndMaterialSetHash(UUID courseId, String materialSetHash);
    Optional<CourseKnowledgeVersion> findByCourse_IdAndStatus(UUID courseId, KnowledgeVersionStatus status);
    Optional<CourseKnowledgeVersion> findFirstByCourse_IdOrderByVersionDesc(UUID courseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select version from CourseKnowledgeVersion version where version.id = :id")
    Optional<CourseKnowledgeVersion> findByIdForUpdate(@Param("id") UUID id);
}
