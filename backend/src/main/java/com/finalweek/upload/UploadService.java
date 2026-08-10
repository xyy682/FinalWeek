package com.finalweek.upload;

import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.CourseService;
import com.finalweek.material.Material;
import com.finalweek.material.MaterialService;
import com.finalweek.material.MaterialType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UploadService {
    private final UploadStateStore states;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final FinalWeekProperties properties;
    private final UploadFilePolicy filePolicy;
    private final CourseService courses;
    private final MaterialService materials;
    private final MaterialFinalizer finalizer;
    private final RedissonClient redisson;

    public UploadService(UploadStateStore states, ObjectStorage storage, StorageProperties storageProperties,
                         FinalWeekProperties properties, UploadFilePolicy filePolicy, CourseService courses,
                         MaterialService materials, MaterialFinalizer finalizer, RedissonClient redisson) {
        this.states = states; this.storage = storage; this.storageProperties = storageProperties;
        this.properties = properties; this.filePolicy = filePolicy; this.courses = courses;
        this.materials = materials; this.finalizer = finalizer; this.redisson = redisson;
    }

    public UploadMetadata initialize(UUID userId, UUID courseId, String filename, long fileSize,
                                     String sha256, MaterialType materialType, String focusNotes) {
        courses.get(userId, courseId);
        var file = filePolicy.validateDeclaration(filename, fileSize);
        var normalizedHash = sha256 == null || sha256.isBlank() ? null : sha256.toLowerCase(java.util.Locale.ROOT);
        if (normalizedHash != null && !normalizedHash.matches("[0-9a-f]{64}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "sha256 必须为 64 位十六进制字符串");
        }
        var chunkSize = storageProperties.chunkSize().toBytes();
        var totalChunks = Math.toIntExact((fileSize + chunkSize - 1) / chunkSize);
        var uploadId = UUID.randomUUID();
        var expiresAt = Instant.now().plus(properties.limits().uploadTtl());
        var metadata = new UploadMetadata(uploadId, userId, courseId, file.filename(), fileSize, chunkSize,
                totalChunks, normalizedHash, file.extension(), file.mediaType(), materialType,
                normalizeNotes(focusNotes), expiresAt);
        states.create(metadata, properties.limits().uploadTtl());
        return metadata;
    }

    public UploadStatus status(UUID userId, UUID uploadId) {
        var completed = states.completed(uploadId);
        if (completed != null) {
            var material = materials.get(userId, completed);
            return new UploadStatus(uploadId, "COMPLETED", java.util.List.of(), material.getId(), null);
        }
        var databaseCompletion = finalizer.completed(uploadId);
        if (databaseCompletion.isPresent()) {
            var material = materials.get(userId, databaseCompletion.get().getId());
            return new UploadStatus(uploadId, "COMPLETED", java.util.List.of(), material.getId(), null);
        }
        var metadata = requireOwned(userId, uploadId);
        return new UploadStatus(uploadId, "UPLOADING", states.chunks(uploadId), null, metadata.expiresAt());
    }

    public void putChunk(UUID userId, UUID uploadId, int chunkIndex, InputStream input, long contentLength) {
        var metadata = requireOwned(userId, uploadId);
        if (chunkIndex < 0 || chunkIndex >= metadata.totalChunks()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CHUNK_INDEX_INVALID", "分片序号超出范围");
        }
        var expectedSize = chunkIndex == metadata.totalChunks() - 1
                ? metadata.fileSize() - metadata.chunkSize() * chunkIndex : metadata.chunkSize();
        if (contentLength != expectedSize) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CHUNK_SIZE_INVALID",
                    "分片大小不正确，期望 " + expectedSize + " 字节");
        }
        storage.put(chunkKey(uploadId, chunkIndex), input, contentLength, "application/octet-stream");
        states.addChunk(uploadId, chunkIndex, java.time.Duration.between(Instant.now(), metadata.expiresAt()));
    }

    public CompleteResult complete(UUID userId, UUID uploadId) {
        var cached = states.completed(uploadId);
        if (cached != null) return new CompleteResult(materials.get(userId, cached), false);
        var uploadLock = redisson.getLock("upload-complete:" + uploadId);
        uploadLock.lock();
        try {
            var prior = finalizer.completed(uploadId);
            if (prior.isPresent()) {
                var material = materials.get(userId, prior.get().getId());
                var remainingMetadata = states.get(uploadId);
                if (remainingMetadata != null && remainingMetadata.userId().equals(userId)) {
                    finishState(remainingMetadata, material);
                }
                return new CompleteResult(material, false);
            }
            var metadata = requireOwned(userId, uploadId);
            var uploaded = states.chunks(uploadId);
            if (uploaded.size() != metadata.totalChunks()
                    || !uploaded.equals(java.util.stream.IntStream.range(0, metadata.totalChunks()).boxed().toList())) {
                throw new BusinessException(HttpStatus.CONFLICT, "CHUNK_MISSING", "仍有分片未上传完成");
            }
            return mergeAndFinalize(metadata);
        } finally {
            if (uploadLock.isHeldByCurrentThread()) uploadLock.unlock();
        }
    }

    private CompleteResult mergeAndFinalize(UploadMetadata metadata) {
        java.nio.file.Path merged = null;
        try {
            merged = Files.createTempFile("finalweek-upload-", ".merge");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DigestOutputStream(Files.newOutputStream(merged), digest)) {
                for (int index = 0; index < metadata.totalChunks(); index++) {
                    try (var input = storage.get(chunkKey(metadata.uploadId(), index))) { input.transferTo(output); }
                }
            }
            if (Files.size(merged) != metadata.fileSize()) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_SIZE_MISMATCH", "合并后的文件大小不一致");
            }
            var hash = HexFormat.of().formatHex(digest.digest());
            if (metadata.expectedSha256() != null && !MessageDigest.isEqual(
                    hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    metadata.expectedSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_HASH_MISMATCH", "完整文件哈希校验失败");
            }
            filePolicy.validateContent(merged, metadata.extension());
            var hashLock = redisson.getLock("material-hash:" + metadata.courseId() + ":" + hash);
            hashLock.lock();
            try {
                var existing = finalizer.duplicate(metadata.courseId(), hash);
                if (existing.isPresent()) {
                    var material = finalizer.recordDuplicate(metadata.uploadId(), existing.get());
                    finishState(metadata, material);
                    return new CompleteResult(material, true);
                }
                var materialId = UUID.randomUUID();
                var objectKey = "materials/" + metadata.courseId() + "/" + materialId + "/original";
                storage.put(objectKey, merged, metadata.mediaType());
                var course = courses.get(metadata.userId(), metadata.courseId());
                var material = finalizer.create(metadata.uploadId(), materialId, course, metadata, objectKey, hash);
                finishState(metadata, material);
                return new CompleteResult(material, false);
            } finally {
                if (hashLock.isHeldByCurrentThread()) hashLock.unlock();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("分片合并失败", exception);
        } finally {
            if (merged != null) try { Files.deleteIfExists(merged); } catch (IOException ignored) { }
        }
    }

    private void finishState(UploadMetadata metadata, Material material) {
        storage.deletePrefix("uploads/" + metadata.uploadId() + "/");
        states.complete(metadata.uploadId(), material.getId(), properties.limits().uploadTtl());
    }

    private UploadMetadata requireOwned(UUID userId, UUID uploadId) {
        var metadata = states.get(uploadId);
        if (metadata == null || metadata.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException(HttpStatus.GONE, "UPLOAD_EXPIRED", "上传会话已过期，请重新开始");
        }
        if (!metadata.userId().equals(userId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "上传会话不存在或无权访问");
        }
        courses.get(userId, metadata.courseId());
        return metadata;
    }

    private String normalizeNotes(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    static String chunkKey(UUID uploadId, int index) { return "uploads/" + uploadId + "/chunks/" + index; }
    public record UploadStatus(UUID uploadId, String status, java.util.List<Integer> uploadedChunks,
                               UUID materialId, Instant expiresAt) {}
    public record CompleteResult(Material material, boolean duplicate) {}
}
