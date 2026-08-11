package com.finalweek.task;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ParseTaskRepository extends JpaRepository<ParseTask, UUID> {
    Optional<ParseTask> findByIdAndUserId(UUID id, UUID userId);
    Optional<ParseTask> findByMaterial_Id(UUID materialId);
    Optional<ParseTask> findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(
            UUID courseId, TaskType taskType, List<TaskStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from ParseTask task where task.courseId = :courseId order by task.createdAt")
    List<ParseTask> lockAllByCourseId(@Param("courseId") UUID courseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'QUEUED', task.updatedAt = CURRENT_TIMESTAMP " +
            "where task.id = :id and task.executionRound = :round and task.status = 'PENDING_PUBLISH'")
    int markQueued(@Param("id") UUID id, @Param("round") int round);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'PUBLISH_FAILED', task.errorCode = :code, " +
            "task.errorMessage = :message, task.updatedAt = CURRENT_TIMESTAMP " +
            "where task.id = :id and task.executionRound = :round and task.status = 'PENDING_PUBLISH'")
    int markPublishFailed(@Param("id") UUID id, @Param("round") int round,
                          @Param("code") String code, @Param("message") String message);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'PROCESSING', " +
            "task.deliveryAttemptCount = task.deliveryAttemptCount + 1, " +
            "task.startedAt = coalesce(task.startedAt, CURRENT_TIMESTAMP), task.updatedAt = CURRENT_TIMESTAMP " +
            "where task.id = :id and task.executionRound = :round and task.deliveryAttemptCount < :maximum " +
            "and task.status in ('PENDING_PUBLISH','PUBLISH_FAILED','QUEUED','RETRYING')")
    int claim(@Param("id") UUID id, @Param("round") int round, @Param("maximum") int maximum);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'RETRYING', task.errorCode = :code, " +
            "task.errorMessage = :message, task.updatedAt = CURRENT_TIMESTAMP " +
            "where task.id = :id and task.executionRound = :round and task.status = 'PROCESSING'")
    int markRetrying(@Param("id") UUID id, @Param("round") int round,
                     @Param("code") String code, @Param("message") String message);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'FAILED', task.errorCode = :code, " +
            "task.errorMessage = :message, task.finishedAt = CURRENT_TIMESTAMP, task.updatedAt = CURRENT_TIMESTAMP " +
            "where task.id = :id and task.executionRound = :round and task.status = 'PROCESSING'")
    int markFailed(@Param("id") UUID id, @Param("round") int round,
                   @Param("code") String code, @Param("message") String message);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'SUCCEEDED', task.currentStage = 'COMPLETED', " +
            "task.errorCode = null, task.errorMessage = null, task.finishedAt = CURRENT_TIMESTAMP, " +
            "task.updatedAt = CURRENT_TIMESTAMP where task.id = :id and task.executionRound = :round " +
            "and task.status = 'PROCESSING'")
    int markSucceeded(@Param("id") UUID id, @Param("round") int round);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'CANCELLED', task.finishedAt = CURRENT_TIMESTAMP, " +
            "task.updatedAt = CURRENT_TIMESTAMP where task.id = :id " +
            "and task.status in ('PENDING_PUBLISH','PUBLISH_FAILED','QUEUED')")
    int cancelUnstarted(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'PENDING_PUBLISH', " +
            "task.publishAttemptCount = task.publishAttemptCount + 1, task.errorCode = null, " +
            "task.errorMessage = null, task.updatedAt = CURRENT_TIMESTAMP where task.id = :id " +
            "and task.status = 'PUBLISH_FAILED'")
    int prepareRepublish(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.status = 'PENDING_PUBLISH', task.executionRound = task.executionRound + 1, " +
            "task.manualRetryCount = task.manualRetryCount + 1, task.publishAttemptCount = task.publishAttemptCount + 1, " +
            "task.deliveryAttemptCount = 0, task.apiAttemptCount = 0, task.errorCode = null, task.errorMessage = null, " +
            "task.startedAt = null, task.finishedAt = null, task.updatedAt = CURRENT_TIMESTAMP " +
            "where task.id = :id and task.status = 'FAILED'")
    int prepareManualRetry(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ParseTask task set task.apiAttemptCount = task.apiAttemptCount + 1, " +
            "task.updatedAt = CURRENT_TIMESTAMP where task.id = :id and task.status = 'PROCESSING'")
    int incrementApiAttempt(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParseTask task set task.currentStage = :stage, task.updatedAt = CURRENT_TIMESTAMP " +
            "where task.id = :id and task.status = 'PROCESSING'")
    int advanceStage(@Param("id") UUID id, @Param("stage") TaskStage stage);

    List<ParseTask> findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(TaskStatus status, Instant before);
}
