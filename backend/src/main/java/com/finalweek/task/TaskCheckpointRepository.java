package com.finalweek.task;

import com.finalweek.common.persistence.BaseRepository;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.apache.ibatis.annotations.Select;

public interface TaskCheckpointRepository extends BaseRepository<TaskCheckpoint> {
    @Select("select * from task_checkpoint where task_id = #{taskId} order by completed_at")
    List<TaskCheckpoint> findAllByTask_IdOrderByCompletedAt(UUID taskId);
    @Select("select * from task_checkpoint where task_id = #{taskId} and stage = #{stage}")
    Optional<TaskCheckpoint> findByTask_IdAndStage(UUID taskId, TaskStage stage);
}
