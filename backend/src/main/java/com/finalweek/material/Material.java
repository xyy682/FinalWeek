package com.finalweek.material;

import com.finalweek.course.Course;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "material")
public class Material {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "content_hash", length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false, length = 30)
    private MaterialType materialType;

    @Column(name = "focus_notes", length = 1000)
    private String focusNotes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MaterialStatus status;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Material() {}

    public Material(UUID id, Course course, String originalFilename, String objectKey, String contentHash,
                    long sizeBytes, String mediaType, MaterialType materialType, String focusNotes) {
        this.id = id;
        this.course = course;
        this.originalFilename = originalFilename;
        this.objectKey = objectKey;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.mediaType = mediaType;
        this.materialType = materialType;
        this.focusNotes = focusNotes;
        this.status = MaterialStatus.PENDING_PUBLISH;
    }

    @PrePersist
    void created() { var now = Instant.now(); createdAt = now; updatedAt = now; }

    @PreUpdate
    void updated() { updatedAt = Instant.now(); }

    public void markDeleted() { deleted = true; contentHash = null; }
    public UUID getId() { return id; }
    public UUID getCourseId() { return course.getId(); }
    public String getOriginalFilename() { return originalFilename; }
    public String getObjectKey() { return objectKey; }
    public String getContentHash() { return contentHash; }
    public long getSizeBytes() { return sizeBytes; }
    public String getMediaType() { return mediaType; }
    public MaterialType getMaterialType() { return materialType; }
    public String getFocusNotes() { return focusNotes; }
    public MaterialStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
