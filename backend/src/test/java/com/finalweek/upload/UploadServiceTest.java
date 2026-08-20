package com.finalweek.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.Course;
import com.finalweek.course.CourseService;
import com.finalweek.material.Material;
import com.finalweek.material.MaterialService;
import com.finalweek.material.MaterialType;
import com.finalweek.task.BackgroundTask;
import com.finalweek.task.TaskDispatchService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.util.unit.DataSize;

class UploadServiceTest {
    private UploadStateStore states;
    private ObjectStorage storage;
    private CourseService courses;
    private MaterialService materials;
    private MaterialFinalizer finalizer;
    private UploadFilePolicy policy;
    private UploadService service;
    private TaskDispatchService dispatcher;
    private RLock lock;
    private UUID userId;
    private UUID courseId;
    private UUID uploadId;

    @BeforeEach
    void setUp() {
        states = mock(UploadStateStore.class); storage = mock(ObjectStorage.class);
        courses = mock(CourseService.class); materials = mock(MaterialService.class);
        finalizer = mock(MaterialFinalizer.class); policy = mock(UploadFilePolicy.class);
        dispatcher = mock(TaskDispatchService.class);
        var redisson = mock(RedissonClient.class); lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        service = new UploadService(states, storage,
                new StorageProperties("http://localhost", "http://localhost", "key", "secret", "bucket", "us-east-1",
                        DataSize.ofBytes(5), Duration.ofMinutes(15), Duration.ofMinutes(10)),
                properties(), policy, courses, materials, finalizer, redisson, dispatcher);
        userId = UUID.randomUUID(); courseId = UUID.randomUUID(); uploadId = UUID.randomUUID();
    }

    @Test
    void writesChunkToObjectStorageBeforeRecordingRedisSet() {
        var metadata = metadata("0123456789".getBytes(StandardCharsets.UTF_8), 5);
        when(states.get(uploadId)).thenReturn(metadata);

        service.putChunk(userId, uploadId, 0, new ByteArrayInputStream(new byte[5]), 5);

        var order = inOrder(storage, states);
        order.verify(storage).put(anyString(), any(ByteArrayInputStream.class), org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq("application/octet-stream"));
        order.verify(states).addChunk(org.mockito.ArgumentMatchers.eq(uploadId), org.mockito.ArgumentMatchers.eq(0), any(Duration.class));
    }

    @Test
    void rejectsCompleteWhenAnyChunkIsMissing() {
        var bytes = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        when(states.get(uploadId)).thenReturn(metadata(bytes, 5));
        when(states.chunks(uploadId)).thenReturn(List.of(0));
        when(finalizer.completed(uploadId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(userId, uploadId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CHUNK_MISSING"));
    }

    @Test
    void mergesChunksInOrderAndPersistsCalculatedHash() throws Exception {
        var bytes = "%PDF-ordered-content".getBytes(StandardCharsets.US_ASCII);
        var metadata = metadata(bytes, 7);
        when(states.get(uploadId)).thenReturn(metadata);
        when(states.chunks(uploadId)).thenReturn(java.util.stream.IntStream.range(0, metadata.totalChunks()).boxed().toList());
        when(finalizer.completed(uploadId)).thenReturn(Optional.empty());
        for (int index = 0; index < metadata.totalChunks(); index++) {
            int start = (int) (index * metadata.chunkSize());
            when(storage.get(UploadService.chunkKey(uploadId, index))).thenReturn(
                    new ByteArrayInputStream(java.util.Arrays.copyOfRange(bytes, start,
                            Math.min(bytes.length, start + (int) metadata.chunkSize()))));
        }
        when(finalizer.duplicate(courseId, metadata.expectedSha256())).thenReturn(Optional.empty());
        var course = mock(Course.class); when(courses.get(userId, courseId)).thenReturn(course);
        var material = mock(Material.class); var materialId = UUID.randomUUID(); when(material.getId()).thenReturn(materialId);
        var task = new BackgroundTask(userId, courseId, material);
        when(finalizer.create(any(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new MaterialFinalizer.FinalizedMaterial(material, task));

        var result = service.complete(userId, uploadId);

        assertThat(result.material()).isSameAs(material);
        verify(policy).validateContent(any(java.nio.file.Path.class), org.mockito.ArgumentMatchers.eq("pdf"));
        verify(finalizer).create(org.mockito.ArgumentMatchers.eq(uploadId), any(), org.mockito.ArgumentMatchers.eq(course),
                org.mockito.ArgumentMatchers.eq(metadata), anyString(), org.mockito.ArgumentMatchers.eq(metadata.expectedSha256()));
        verify(states).complete(uploadId, materialId, Duration.ofHours(24));
        verify(storage).deletePrefix("uploads/" + uploadId + "/");
    }

    @Test
    void courseHashDuplicateReturnsExistingMaterialWithoutSecondFinalObject() throws Exception {
        var bytes = "%PDF-duplicate".getBytes(StandardCharsets.US_ASCII);
        var metadata = metadata(bytes, bytes.length);
        when(states.get(uploadId)).thenReturn(metadata); when(states.chunks(uploadId)).thenReturn(List.of(0));
        when(finalizer.completed(uploadId)).thenReturn(Optional.empty());
        when(storage.get(UploadService.chunkKey(uploadId, 0))).thenReturn(new ByteArrayInputStream(bytes));
        var existing = mock(Material.class); when(existing.getId()).thenReturn(UUID.randomUUID());
        when(finalizer.duplicate(courseId, metadata.expectedSha256())).thenReturn(Optional.of(existing));
        when(finalizer.recordDuplicate(uploadId, existing)).thenReturn(existing);
        var existingTask = new BackgroundTask(userId, courseId, existing);
        when(finalizer.taskFor(existing)).thenReturn(Optional.of(existingTask));

        var result = service.complete(userId, uploadId);

        assertThat(result.duplicate()).isTrue();
        verify(storage, never()).put(anyString(), any(java.nio.file.Path.class), anyString());
    }

    private UploadMetadata metadata(byte[] bytes, long chunkSize) {
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        return new UploadMetadata(uploadId, userId, courseId, "test.pdf", bytes.length, chunkSize,
                Math.toIntExact((bytes.length + chunkSize - 1) / chunkSize), hash, "pdf", "application/pdf",
                MaterialType.COURSEWARE, null, Instant.now().plus(Duration.ofHours(1)));
    }

    private FinalWeekProperties properties() {
        return new FinalWeekProperties(
                new FinalWeekProperties.Auth(Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(10), 5, 5),
                new FinalWeekProperties.Limits(8, Duration.ofHours(24), 100, 2048, Duration.ofHours(2), 5, 30, 5, 20),
                new FinalWeekProperties.Retrieval(20, 20, 8, 60, 512, 64, 10, 1024,
                        "http://localhost:6333", "segments", java.nio.file.Path.of("build/lucene")),
                new FinalWeekProperties.Ai("https://example.com", "", 3, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60), 20, "asr", "ocr", "embedding", "llm"));
    }
}
