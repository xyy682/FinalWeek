package com.finalweek.upload;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import com.finalweek.material.Material;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.Instant;
import java.util.UUID;

public class UploadCompletion {
    @TableId(type = IdType.INPUT)
    private UUID uploadId;

    private UUID materialId;

    private Instant createdAt;

    protected UploadCompletion() {}
    public UploadCompletion(UUID uploadId, Material material) { this.uploadId = uploadId; this.materialId = material.getId(); }
    @BeforeInsert void created() { createdAt = Instant.now(); }
    public UUID getMaterialId() { return materialId; }
}
