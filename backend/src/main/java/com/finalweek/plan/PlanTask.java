package com.finalweek.plan;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class PlanTask {
    private UUID id;
    private UUID planId;
    private UUID outlineNodeId;
    private String knowledgeTitle;
    private LocalDate plannedDate;
    private int estimatedMinutes;
    private boolean completed;
    private Instant completedAt;
    private int position;
    protected PlanTask() {}
    public PlanTask(StudyPlan plan, UUID outlineNodeId, String knowledgeTitle, LocalDate plannedDate,
                    int estimatedMinutes, int position) {
        this.id = UUID.nameUUIDFromBytes(("plan-task:" + plan.getId() + ":" + plan.getVersion() + ":" + position)
                .getBytes(StandardCharsets.UTF_8));
        this.planId = plan.getId(); this.outlineNodeId = outlineNodeId; this.knowledgeTitle = knowledgeTitle;
        this.plannedDate = plannedDate; this.estimatedMinutes = estimatedMinutes; this.position = position;
    }
    public void setCompleted(boolean value) {
        completed = value; completedAt = value ? Instant.now() : null;
    }
    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public UUID getOutlineNodeId() { return outlineNodeId; }
    public String getKnowledgeTitle() { return knowledgeTitle; }
    public LocalDate getPlannedDate() { return plannedDate; }
    public int getEstimatedMinutes() { return estimatedMinutes; }
    public boolean isCompleted() { return completed; }
    public Instant getCompletedAt() { return completedAt; }
    public int getPosition() { return position; }
}
