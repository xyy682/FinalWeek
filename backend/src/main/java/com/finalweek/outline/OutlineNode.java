package com.finalweek.outline;

import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Entity
@Table(name = "outline_node")
public class OutlineNode {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "outline_id", nullable = false)
    private Outline outline;
    @Column(name = "parent_id") private UUID parentId;
    @Column(nullable = false, length = 200) private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private OutlineImportance importance;
    @Column(nullable = false) private int position;
    @Column(name = "source_refs_json", nullable = false, columnDefinition = "json") private String sourceRefsJson;
    @Column(name = "importance_manually_adjusted", nullable = false) private boolean importanceManuallyAdjusted;
    protected OutlineNode() {}
    public OutlineNode(Outline outline, UUID parentId, String path, String title, OutlineImportance importance,
                       int position, String sourceRefsJson) {
        this.id = UUID.nameUUIDFromBytes(("outline-node:" + outline.getId() + ":" + outline.getGenerationVersion()
                + ":" + path).getBytes(StandardCharsets.UTF_8));
        this.outline = outline; this.parentId = parentId; this.title = title; this.importance = importance;
        this.position = position; this.sourceRefsJson = sourceRefsJson;
    }
    public void adjust(OutlineImportance value) { importance = value; importanceManuallyAdjusted = true; }
    public UUID getId() { return id; }
    public UUID getOutlineId() { return outline.getId(); }
    public UUID getParentId() { return parentId; }
    public String getTitle() { return title; }
    public OutlineImportance getImportance() { return importance; }
    public int getPosition() { return position; }
    public String getSourceRefsJson() { return sourceRefsJson; }
    public boolean isImportanceManuallyAdjusted() { return importanceManuallyAdjusted; }
}
