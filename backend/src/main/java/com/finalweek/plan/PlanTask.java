package com.finalweek.plan;

import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "plan_task")
public class PlanTask {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "plan_id", nullable = false)
    private StudyPlan plan;
    @Column(name = "outline_node_id", nullable = false) private UUID outlineNodeId;
    @Column(name = "knowledge_title", nullable = false, length = 200) private String knowledgeTitle;
    @Column(name = "planned_date", nullable = false) private LocalDate plannedDate;
    @Column(name = "estimated_minutes", nullable = false) private int estimatedMinutes;
    @Column(nullable = false) private boolean completed;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(nullable = false) private int position;
    protected PlanTask() {}
    public PlanTask(StudyPlan plan, UUID outlineNodeId, String knowledgeTitle, LocalDate plannedDate,
                    int estimatedMinutes, int position) {
        this.id = UUID.nameUUIDFromBytes(("plan-task:" + plan.getId() + ":" + plan.getVersion() + ":" + position)
                .getBytes(StandardCharsets.UTF_8));
        this.plan = plan; this.outlineNodeId = outlineNodeId; this.knowledgeTitle = knowledgeTitle;
        this.plannedDate = plannedDate; this.estimatedMinutes = estimatedMinutes; this.position = position;
    }
    public void setCompleted(boolean value) {
        completed = value; completedAt = value ? Instant.now() : null;
    }
    public UUID getId() { return id; }
    public UUID getPlanId() { return plan.getId(); }
    public UUID getOutlineNodeId() { return outlineNodeId; }
    public String getKnowledgeTitle() { return knowledgeTitle; }
    public LocalDate getPlannedDate() { return plannedDate; }
    public int getEstimatedMinutes() { return estimatedMinutes; }
    public boolean isCompleted() { return completed; }
    public Instant getCompletedAt() { return completedAt; }
    public int getPosition() { return position; }
}
