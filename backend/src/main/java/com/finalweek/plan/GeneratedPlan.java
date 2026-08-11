package com.finalweek.plan;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GeneratedPlan(List<Task> tasks) {
    public record Task(UUID outlineNodeId, LocalDate plannedDate, int estimatedMinutes) {}
}
