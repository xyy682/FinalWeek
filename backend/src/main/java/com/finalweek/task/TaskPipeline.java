package com.finalweek.task;
public interface TaskPipeline {
    TaskType type();
    void execute(BackgroundTask task);
}
