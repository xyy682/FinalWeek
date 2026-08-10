package com.finalweek.task;

import java.time.Instant;
import java.util.UUID;

public record TaskMessage(UUID taskId, int executionRound, String messageId, Instant publishedAt) {
    public static TaskMessage create(ParseTask task) {
        return new TaskMessage(task.getId(), task.getExecutionRound(), UUID.randomUUID().toString(), Instant.now());
    }
}
