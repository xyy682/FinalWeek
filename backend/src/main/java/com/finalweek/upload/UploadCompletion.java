package com.finalweek.upload;

import com.finalweek.material.Material;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_completion")
public class UploadCompletion {
    @Id
    @Column(name = "upload_id")
    private UUID uploadId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UploadCompletion() {}
    public UploadCompletion(UUID uploadId, Material material) { this.uploadId = uploadId; this.material = material; }
    @PrePersist void created() { createdAt = Instant.now(); }
    public Material getMaterial() { return material; }
}
