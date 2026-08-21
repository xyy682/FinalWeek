package com.finalweek.knowledgeversion;

import java.util.UUID;

public class CourseKnowledgeVersionMaterial {
    private UUID knowledgeVersionId;
    private UUID materialId;
    private int position;

    protected CourseKnowledgeVersionMaterial() {}
    public CourseKnowledgeVersionMaterial(UUID knowledgeVersionId, UUID materialId, int position) {
        this.knowledgeVersionId = knowledgeVersionId; this.materialId = materialId; this.position = position;
    }
    public UUID getMaterialId() { return materialId; }
    public int getPosition() { return position; }
}
