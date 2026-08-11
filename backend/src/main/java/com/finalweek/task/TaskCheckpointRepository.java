package com.finalweek.task;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCheckpointRepository extends JpaRepository<TaskCheckpoint, UUID> {
    List<TaskCheckpoint> findAllByTask_IdOrderByCompletedAt(UUID taskId);
    Optional<TaskCheckpoint> findByTask_IdAndStage(UUID taskId, TaskStage stage);
}
