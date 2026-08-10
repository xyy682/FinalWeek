package com.finalweek.task;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedTaskRepository extends JpaRepository<FailedTask, UUID> {
    Optional<FailedTask> findByMessageId(String messageId);
    Optional<FailedTask> findTopByTask_IdOrderByCreatedAtDesc(UUID taskId);
}
