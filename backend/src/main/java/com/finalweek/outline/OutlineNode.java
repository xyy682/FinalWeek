package com.finalweek.outline;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class OutlineNode {
    private UUID id;
    private UUID outlineId;
    private UUID parentId;
    private String title;
    private OutlineImportance importance;
    private int position;
    private String sourceRefsJson;
    private boolean importanceManuallyAdjusted;
    protected OutlineNode() {}
    public OutlineNode(Outline outline, UUID parentId, String path, String title, OutlineImportance importance,
                       int position, String sourceRefsJson) {
        this.id = UUID.nameUUIDFromBytes(("outline-node:" + outline.getId() + ":" + outline.getGenerationVersion()
                + ":" + path).getBytes(StandardCharsets.UTF_8));
        this.outlineId = outline.getId(); this.parentId = parentId; this.title = title; this.importance = importance;
        this.position = position; this.sourceRefsJson = sourceRefsJson;
    }
    public void adjust(OutlineImportance value) { importance = value; importanceManuallyAdjusted = true; }
    public UUID getId() { return id; }
    public UUID getOutlineId() { return outlineId; }
    public UUID getParentId() { return parentId; }
    public String getTitle() { return title; }
    public OutlineImportance getImportance() { return importance; }
    public int getPosition() { return position; }
    public String getSourceRefsJson() { return sourceRefsJson; }
    public boolean isImportanceManuallyAdjusted() { return importanceManuallyAdjusted; }
}
