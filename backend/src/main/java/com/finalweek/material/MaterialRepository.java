package com.finalweek.material;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialRepository extends JpaRepository<Material, UUID> {
    List<Material> findAllByCourse_IdAndCourse_User_IdAndDeletedFalseOrderByCreatedAtDesc(UUID courseId, UUID userId);
    Optional<Material> findByIdAndCourse_User_IdAndDeletedFalse(UUID id, UUID userId);
    Optional<Material> findByCourse_IdAndContentHashAndDeletedFalse(UUID courseId, String contentHash);
    long countByCourse_IdAndDeletedFalse(UUID courseId);
    Optional<Material> findTopByCourse_IdAndDeletedFalseOrderByUpdatedAtDesc(UUID courseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select material from Material material where material.id = :id and material.course.user.id = :userId and material.deleted = false")
    Optional<Material> findOwnedByIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
}
