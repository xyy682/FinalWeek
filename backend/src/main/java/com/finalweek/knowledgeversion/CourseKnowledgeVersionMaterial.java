package com.finalweek.knowledgeversion;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "course_knowledge_version_material")
@IdClass(CourseKnowledgeVersionMaterialId.class)
public class CourseKnowledgeVersionMaterial {
    @Id @Column(name = "knowledge_version_id") private UUID knowledgeVersionId;
    @Id @Column(name = "material_id") private UUID materialId;
    @Column(nullable = false) private int position;

    protected CourseKnowledgeVersionMaterial() {}
    public CourseKnowledgeVersionMaterial(UUID knowledgeVersionId, UUID materialId, int position) {
        this.knowledgeVersionId = knowledgeVersionId; this.materialId = materialId; this.position = position;
    }
    public UUID getMaterialId() { return materialId; }
    public int getPosition() { return position; }
}
