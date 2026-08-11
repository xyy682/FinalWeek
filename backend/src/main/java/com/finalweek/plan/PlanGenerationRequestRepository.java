package com.finalweek.plan;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PlanGenerationRequestRepository extends JpaRepository<PlanGenerationRequest, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from PlanGenerationRequest request where request.userId = :userId " +
            "and request.courseId = :courseId and request.idempotencyKey = :key")
    Optional<PlanGenerationRequest> findForUpdate(@Param("userId") UUID userId,
                                                   @Param("courseId") UUID courseId,
                                                   @Param("key") String key);
}
