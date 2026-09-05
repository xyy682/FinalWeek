package com.finalweek.task;

import com.finalweek.common.persistence.BaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BackgroundTaskRepository extends BaseRepository<BackgroundTask> {
    @Select("select * from background_task where id = #{id} and user_id = #{userId}")
    Optional<BackgroundTask> findByIdAndUserId(UUID id, UUID userId);
    @Select("select * from background_task where material_id = #{materialId}")
    Optional<BackgroundTask> findByMaterial_Id(UUID materialId);
    @Select({"<script>", "select * from background_task where course_id = #{courseId} and task_type = #{taskType}",
            "and status in <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>",
            "order by created_at desc limit 1", "</script>"})
    Optional<BackgroundTask> findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(
            UUID courseId, TaskType taskType, List<TaskStatus> statuses);
    @Select({"<script>", "select * from background_task where user_id = #{userId} and visible_in_global_drawer = true",
            "and status in <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>",
            "order by updated_at desc", "</script>"})
    List<BackgroundTask> findAllByUserIdAndVisibleInGlobalDrawerTrueAndStatusInOrderByUpdatedAtDesc(
            UUID userId, List<TaskStatus> statuses);
    @Select("select * from background_task where course_id = #{courseId} order by created_at for update")
    List<BackgroundTask> lockAllByCourseId(UUID courseId);

    @Update("update background_task set status = 'QUEUED', updated_at = current_timestamp " +
            "where id = #{id} and execution_round = #{round} and status = 'PENDING_PUBLISH'")
    int markQueued(UUID id, int round);
    @Update("update background_task set status = 'PUBLISH_FAILED', error_code = #{code}, error_message = #{message}, " +
            "updated_at = current_timestamp where id = #{id} and execution_round = #{round} " +
            "and status = 'PENDING_PUBLISH'")
    int markPublishFailed(UUID id, int round, String code, String message);
    @Update("update background_task set status = 'PROCESSING', delivery_attempt_count = delivery_attempt_count + 1, " +
            "processing_owner = #{owner}, processing_lease_until = #{leaseUntil}, " +
            "started_at = coalesce(started_at, current_timestamp), updated_at = current_timestamp " +
            "where id = #{id} and execution_round = #{round} and delivery_attempt_count < #{maximum} " +
            "and status in ('PENDING_PUBLISH','PUBLISH_FAILED','QUEUED','RETRYING')")
    int claim(UUID id, int round, int maximum, String owner, Instant leaseUntil);
    @Update("update background_task set processing_lease_until = #{leaseUntil}, updated_at = current_timestamp " +
            "where id = #{id} and execution_round = #{round} and status = 'PROCESSING' " +
            "and processing_owner = #{owner}")
    int renewProcessingLease(UUID id, int round, String owner, Instant leaseUntil);
    @Select("select * from background_task where status = 'PROCESSING' and processing_lease_until <= #{before} " +
            "order by processing_lease_until asc limit 100")
    List<BackgroundTask> findExpiredProcessingLeases(Instant before);
    @Update("update background_task set status = 'PENDING_PUBLISH', processing_owner = null, " +
            "processing_lease_until = null, publish_attempt_count = publish_attempt_count + 1, " +
            "error_code = 'PROCESSING_LEASE_EXPIRED', error_message = '任务执行租约过期，正在重新投递', " +
            "updated_at = current_timestamp where id = #{id} and execution_round = #{round} " +
            "and status = 'PROCESSING' and processing_owner = #{owner} and processing_lease_until <= #{before}")
    int recoverExpiredProcessingLease(UUID id, int round, String owner, Instant before);
    @Update("update background_task set status = 'RETRYING', processing_owner = null, processing_lease_until = null, " +
            "error_code = #{code}, error_message = #{message}, updated_at = current_timestamp where id = #{id} " +
            "and execution_round = #{round} and status = 'PROCESSING' and processing_owner = #{owner}")
    int markRetrying(UUID id, int round, String owner, String code, String message);
    @Update("update background_task set status = 'FAILED', processing_owner = null, processing_lease_until = null, " +
            "error_code = #{code}, error_message = #{message}, finished_at = current_timestamp, " +
            "updated_at = current_timestamp where id = #{id} and execution_round = #{round} " +
            "and status = 'PROCESSING' and processing_owner = #{owner}")
    int markFailed(UUID id, int round, String owner, String code, String message);
    @Update("update background_task set status = 'SUCCEEDED', current_stage = 'COMPLETED', processing_owner = null, " +
            "processing_lease_until = null, error_code = null, error_message = null, finished_at = current_timestamp, " +
            "updated_at = current_timestamp where id = #{id} and execution_round = #{round} " +
            "and status = 'PROCESSING' and processing_owner = #{owner}")
    int markSucceeded(UUID id, int round, String owner);
    @Update("update background_task set status = 'CANCELLED', finished_at = current_timestamp, " +
            "updated_at = current_timestamp where id = #{id} and status in ('PENDING_PUBLISH','PUBLISH_FAILED','QUEUED')")
    int cancelUnstarted(UUID id);
    @Update("update background_task set status = 'CANCELLED', finished_at = current_timestamp, " +
            "updated_at = current_timestamp where id = #{id} and status = 'QUEUED'")
    int cancelQueued(UUID id);
    @Update("update background_task set status = 'PENDING_PUBLISH', publish_attempt_count = publish_attempt_count + 1, " +
            "error_code = null, error_message = null, updated_at = current_timestamp where id = #{id} " +
            "and status = 'PUBLISH_FAILED'")
    int prepareRepublish(UUID id);
    @Update("update background_task set status = 'PENDING_PUBLISH', execution_round = execution_round + 1, " +
            "manual_retry_count = manual_retry_count + 1, publish_attempt_count = publish_attempt_count + 1, " +
            "delivery_attempt_count = 0, api_attempt_count = 0, processing_owner = null, processing_lease_until = null, " +
            "error_code = null, error_message = null, started_at = null, finished_at = null, " +
            "updated_at = current_timestamp where id = #{id} and status = 'FAILED'")
    int prepareManualRetry(UUID id);
    @Update("update background_task set api_attempt_count = api_attempt_count + 1, updated_at = current_timestamp " +
            "where id = #{id} and status = 'PROCESSING'")
    int incrementApiAttempt(UUID id);
    @Update("update background_task set current_stage = #{stage}, updated_at = current_timestamp " +
            "where id = #{id} and status = 'PROCESSING'")
    int advanceStage(UUID id, TaskStage stage);
    @Select("select * from background_task where status = #{status} and updated_at < #{before} " +
            "order by updated_at asc limit 100")
    List<BackgroundTask> findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(TaskStatus status, Instant before);
}