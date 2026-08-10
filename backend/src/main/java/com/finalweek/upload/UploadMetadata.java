package com.finalweek.upload;

import com.finalweek.material.MaterialType;
import java.time.Instant;
import java.util.UUID;

public record UploadMetadata(
        UUID uploadId,
        UUID userId,
        UUID courseId,
        String filename,
        long fileSize,
        long chunkSize,
        int totalChunks,
        String expectedSha256,
        String extension,
        String mediaType,
        MaterialType materialType,
        String focusNotes,
        Instant expiresAt) {
}
