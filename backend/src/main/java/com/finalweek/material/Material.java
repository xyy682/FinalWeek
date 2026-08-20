package com.finalweek.material;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import com.finalweek.course.Course;
import java.time.Instant;
import java.util.UUID;

public class Material {

    private UUID id;

    private UUID courseId;

    private String originalFilename;

    private String objectKey;
    private String previewObjectKey;

    private String contentHash;

    private long sizeBytes;
    private Long durationMs;

    private String mediaType;

    private MaterialType materialType;

    private String focusNotes;

    private MaterialStatus status;
    private String parseWarning;

    private boolean deleted;

    private Instant createdAt;

    private Instant updatedAt;

    protected Material() {}

    public Material(UUID id, Course course, String originalFilename, String objectKey, String contentHash,
                    long sizeBytes, String mediaType, MaterialType materialType, String focusNotes) {
        this.id = id;
        this.courseId = course.getId();
        this.originalFilename = originalFilename;
        this.objectKey = objectKey;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.mediaType = mediaType;
        this.materialType = materialType;
        this.focusNotes = focusNotes;
        this.status = MaterialStatus.PENDING_PUBLISH;
    }

    @BeforeInsert
    void created() { var now = Instant.now(); createdAt = now; updatedAt = now; }

    @BeforeUpdate
    void updated() { updatedAt = Instant.now(); }

    public void markDeleted() { deleted = true; contentHash = null; }
    public void extracted(String previewObjectKey, Long durationMs, String warning) {
        this.previewObjectKey = previewObjectKey; this.durationMs = durationMs; this.parseWarning = warning;
    }
    public UUID getId() { return id; }
    public UUID getCourseId() { return courseId; }
    public String getOriginalFilename() { return originalFilename; }
    public String getObjectKey() { return objectKey; }
    public String getPreviewObjectKey() { return previewObjectKey; }
    public String getContentHash() { return contentHash; }
    public long getSizeBytes() { return sizeBytes; }
    public Long getDurationMs() { return durationMs; }
    public String getMediaType() { return mediaType; }
    public MaterialType getMaterialType() { return materialType; }
    public String getFocusNotes() { return focusNotes; }
    public MaterialStatus getStatus() { return status; }
    public String getParseWarning() { return parseWarning; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
