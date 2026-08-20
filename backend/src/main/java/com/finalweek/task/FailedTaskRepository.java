package com.finalweek.task;

import com.finalweek.common.persistence.BaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface FailedTaskRepository extends BaseRepository<FailedTask> {
    @Select("select * from failed_task where message_id = #{messageId}")
    Optional<FailedTask> findByMessageId(String messageId);
    @Select("select * from failed_task where task_id = #{taskId} order by created_at desc limit 1")
    Optional<FailedTask> findTopByTask_IdOrderByCreatedAtDesc(UUID taskId);
}
