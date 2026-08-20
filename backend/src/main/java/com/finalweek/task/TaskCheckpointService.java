package com.finalweek.task;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCheckpointService {
    private final BackgroundTaskRepository tasks;
    private final TaskCheckpointRepository checkpoints;
    private final TaskProgressService progress;

    public TaskCheckpointService(BackgroundTaskRepository tasks, TaskCheckpointRepository checkpoints,
                                 TaskProgressService progress) {
        this.tasks = tasks; this.checkpoints = checkpoints; this.progress = progress;
    }

    @Transactional(readOnly = true)
    public boolean completed(UUID taskId, TaskStage stage) {
        return checkpoints.findByTask_IdAndStage(taskId, stage).isPresent();
    }

    @Transactional(readOnly = true)
    public String resultJson(UUID taskId, TaskStage stage) {
        return checkpoints.findByTask_IdAndStage(taskId, stage).orElseThrow().getResultJson();
    }

    @Transactional
    public void complete(UUID taskId, TaskStage stage, String resultJson) {
        if (checkpoints.findByTask_IdAndStage(taskId, stage).isPresent()) return;
        var task = tasks.findById(taskId).orElseThrow();
        checkpoints.save(new TaskCheckpoint(task, stage, null, resultJson));
        tasks.advanceStage(taskId, stage);
        progress.publish(tasks.findById(taskId).orElseThrow());
    }
}
