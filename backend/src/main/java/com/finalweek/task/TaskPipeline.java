package com.finalweek.task;
public interface TaskPipeline {
    TaskType type();
    void execute(ParseTask task);
}
