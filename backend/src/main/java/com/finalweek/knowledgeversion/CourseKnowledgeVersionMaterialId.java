package com.finalweek.knowledgeversion;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class CourseKnowledgeVersionMaterialId implements Serializable {
    private UUID knowledgeVersionId;
    private UUID materialId;
    protected CourseKnowledgeVersionMaterialId() {}
    public CourseKnowledgeVersionMaterialId(UUID knowledgeVersionId, UUID materialId) {
        this.knowledgeVersionId = knowledgeVersionId; this.materialId = materialId;
    }
    @Override public boolean equals(Object value) {
        return value instanceof CourseKnowledgeVersionMaterialId other
                && Objects.equals(knowledgeVersionId, other.knowledgeVersionId)
                && Objects.equals(materialId, other.materialId);
    }
    @Override public int hashCode() { return Objects.hash(knowledgeVersionId, materialId); }
}
