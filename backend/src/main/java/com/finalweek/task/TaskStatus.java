package com.finalweek.task;

public enum TaskStatus {
    PENDING_PUBLISH,
    PUBLISH_FAILED,
    QUEUED,
    PROCESSING,
    RETRYING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
