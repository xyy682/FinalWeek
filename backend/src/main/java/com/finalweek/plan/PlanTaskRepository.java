package com.finalweek.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PlanTaskRepository extends JpaRepository<PlanTask, UUID> {
    List<PlanTask> findAllByPlan_IdOrderByPlannedDateAscPositionAsc(UUID planId);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PlanTask task where task.plan.id = :planId")
    int deleteAllForPlan(@Param("planId") UUID planId);
    @Query("select task from PlanTask task join task.plan plan join plan.course course " +
            "where task.id = :id and course.user.id = :userId and course.deleted = false")
    Optional<PlanTask> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);
}
