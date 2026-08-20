package com.finalweek.course;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import com.finalweek.auth.UserAccount;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.Instant;
import java.util.UUID;

public class Course {

    private UUID id;

    private UUID userId;

    private String name;

    private boolean deleted;

    @TableField("outline_generation_seq")
    private long outlineGenerationSequence;

    private UUID currentKnowledgeVersionId;

    private Instant createdAt;

    private Instant updatedAt;

    protected Course() {}

    public Course(UserAccount user, String name) {
        this.userId = user.getId();
        this.name = name;
    }

    @BeforeInsert
    void created() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @BeforeUpdate
    void updated() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() { return userId; }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void markDeleted() {
        this.deleted = true;
    }

    public long nextOutlineGeneration() { return ++outlineGenerationSequence; }
    public long getOutlineGenerationSequence() { return outlineGenerationSequence; }
    public UUID getCurrentKnowledgeVersionId() { return currentKnowledgeVersionId; }
    public void publishKnowledgeVersion(UUID knowledgeVersionId) { this.currentKnowledgeVersionId = knowledgeVersionId; }
}
