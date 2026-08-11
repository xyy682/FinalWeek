package com.finalweek.task;

import com.finalweek.common.api.BusinessException;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.MaterialStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskStateService {
    private final ParseTaskRepository tasks;
    private final MaterialRepository materials;
    private final FailedTaskRepository failedTasks;
    private final TaskCheckpointRepository checkpoints;
    private final TaskProgressService progress;

    public TaskStateService(ParseTaskRepository tasks, MaterialRepository materials,
                            FailedTaskRepository failedTasks, TaskCheckpointRepository checkpoints,
                            TaskProgressService progress) {
        this.tasks = tasks; this.materials = materials; this.failedTasks = failedTasks;
        this.checkpoints = checkpoints; this.progress = progress;
    }

    @Transactional(readOnly = true)
    public ParseTask owned(UUID userId, UUID taskId) {
        return tasks.findByIdAndUserId(taskId, userId).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或无权访问"));
    }

    @Transactional
    public boolean queued(UUID id, int round) { return changed(tasks.markQueued(id, round), id, MaterialStatus.QUEUED); }

    @Transactional
    public boolean publishFailed(UUID id, int round, String code, String message) {
        return changed(tasks.markPublishFailed(id, round, code, truncate(message)), id, MaterialStatus.PUBLISH_FAILED);
    }

    @Transactional
    public boolean claim(UUID id, int round, int maximum) {
        return changed(tasks.claim(id, round, maximum), id, MaterialStatus.PROCESSING);
    }

    @Transactional
    public void retrying(UUID id, int round, String code, String message) {
        changed(tasks.markRetrying(id, round, code, truncate(message)), id, MaterialStatus.RETRYING);
    }

    @Transactional
    public void failed(UUID id, int round, String messageId, String code, String message) {
        if (tasks.markFailed(id, round, code, truncate(message)) == 1) {
            var task = tasks.findById(id).orElseThrow();
            if (task.getTaskType() == TaskType.PARSE_MATERIAL) {
                materials.updateStatus(task.getMaterialId(), MaterialStatus.FAILED);
            }
            if (failedTasks.findByMessageId(messageId).isEmpty()) failedTasks.save(new FailedTask(task, messageId, truncate(message)));
            progress.publish(task);
        }
    }

    @Transactional
    public void succeeded(UUID id, int round) {
        if (tasks.markSucceeded(id, round) != 1) return;
        var task = tasks.findById(id).orElseThrow();
        if (checkpoints.findByTask_IdAndStage(id, TaskStage.COMPLETED).isEmpty()) {
            checkpoints.save(new TaskCheckpoint(task, TaskStage.COMPLETED, null, "{\"indexed\":true}"));
        }
        if (task.getTaskType() == TaskType.PARSE_MATERIAL) {
            materials.updateStatus(task.getMaterialId(), MaterialStatus.SUCCEEDED);
        }
        failedTasks.findTopByTask_IdOrderByCreatedAtDesc(id).ifPresent(FailedTask::markResolved);
        progress.publish(task);
    }

    @Transactional
    public ParseTask cancel(UUID userId, UUID id) {
        owned(userId, id);
        if (tasks.cancelUnstarted(id) != 1) throw new BusinessException(HttpStatus.CONFLICT,
                "TASK_NOT_CANCELLABLE", "仅未开始的任务可以取消");
        return refresh(id, MaterialStatus.CANCELLED);
    }

    @Transactional
    public ParseTask prepareRepublish(UUID userId, UUID id) {
        owned(userId, id);
        if (tasks.prepareRepublish(id) != 1) throw new BusinessException(HttpStatus.CONFLICT,
                "TASK_NOT_REPUBLISHABLE", "仅发布失败的任务可以重新发布");
        return refresh(id, MaterialStatus.PENDING_PUBLISH);
    }

    @Transactional
    public ParseTask prepareManualRetry(UUID userId, UUID id) {
        owned(userId, id);
        if (tasks.prepareManualRetry(id) != 1) throw new BusinessException(HttpStatus.CONFLICT,
                "TASK_NOT_RETRYABLE", "仅执行失败的任务可以人工重试");
        failedTasks.findTopByTask_IdOrderByCreatedAtDesc(id).ifPresent(FailedTask::markRedelivered);
        return refresh(id, MaterialStatus.PENDING_PUBLISH);
    }

    private boolean changed(int count, UUID id, MaterialStatus status) {
        if (count != 1) return false;
        refresh(id, status); return true;
    }
    private ParseTask refresh(UUID id, MaterialStatus status) {
        var task = tasks.findById(id).orElseThrow();
        if (task.getTaskType() == TaskType.PARSE_MATERIAL && task.getMaterialId() != null) {
            materials.updateStatus(task.getMaterialId(), status);
        }
        progress.publish(task);
        return task;
    }
    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
