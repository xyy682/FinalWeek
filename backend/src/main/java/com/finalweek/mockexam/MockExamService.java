package com.finalweek.mockexam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.task.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.finalweek.upload.StorageProperties;
import com.finalweek.upload.ObjectStorage;
import java.time.Instant;
import java.io.InputStream;

@Service
public class MockExamService {
    private final MockExamCoordinator coordinator;
    private final MockExamRateLimiter limiter;
    private final TaskDispatchService dispatcher;
    private final ObjectMapper mapper;
    private final MockExamProperties properties;
    private final MockExamCleanupService cleanup;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    public MockExamService(MockExamCoordinator coordinator, MockExamRateLimiter limiter,
                           TaskDispatchService dispatcher, ObjectMapper mapper, MockExamProperties properties,
                           MockExamCleanupService cleanup, ObjectStorage storage, StorageProperties storageProperties) {
        this.coordinator = coordinator; this.limiter = limiter; this.dispatcher = dispatcher;
        this.mapper = mapper; this.properties = properties;
        this.cleanup = cleanup; this.storage = storage; this.storageProperties = storageProperties;
    }
    public Accepted create(UUID userId, UUID courseId, String key, MockExamRequestNormalizer.Request request) {
        return accept(userId, courseId, key, request, null);
    }
    public Accepted retry(UUID userId, UUID examId, String key, MockExamRequestNormalizer.Request request) {
        var original = coordinator.owned(userId, examId);
        if (original.getStatus() != TaskStatus.FAILED && original.getStatus() != TaskStatus.CANCELLED)
            throw new BusinessException(HttpStatus.CONFLICT,
                "MOCK_EXAM_NOT_RETRYABLE", "仅失败或已取消的模拟卷可以修改后重试");
        return accept(userId, original.getCourseId(), key, request, original.getId());
    }
    private Accepted accept(UUID userId, UUID courseId, String key, MockExamRequestNormalizer.Request request,
                            UUID retryOfId) {
        var created = coordinator.create(userId, courseId, key, request, retryOfId);
        if (!created.replay()) {
            try { limiter.acquire(userId); }
            catch (RuntimeException exception) { dispatcher.rejectBeforePublish(created.task(), exception); throw exception; }
            dispatcher.dispatchAfterRateLimit(created.task());
        }
        return new Accepted(created.exam().getId(), created.task().getId(),
                TaskProgressService.TaskView.from(created.task()), created.replay());
    }
    public Detail detail(UUID userId, UUID examId) {
        var value = coordinator.owned(userId, examId);
        try { return new Detail(MockExamCoordinator.Summary.from(value, null), mapper.readValue(value.getRequestJson(),
                MockExamRequestNormalizer.Normalized.class), value.getKnowledgeVersionId(), value.getTaskId(),
                value.getPaperObjectKey() != null && value.getAnswerObjectKey() != null); }
        catch (Exception exception) { throw new IllegalStateException("模拟卷参数损坏", exception); }
    }
    public MockExamCoordinator.PageView page(UUID userId, UUID courseId, int page, Integer size) {
        return coordinator.page(userId, courseId, page, size == null ? properties.defaultPageSize() : size);
    }
    @Transactional
    public void delete(UUID userId, UUID examId) { var exam = coordinator.owned(userId, examId);
        coordinator.delete(userId, examId); cleanup.enqueue(exam); }
    public FileLink file(UUID userId, UUID examId, FileKind kind) {
        var exam = coordinator.owned(userId, examId);
        var key = readyKey(exam, kind);
        var ttl = storageProperties.previewUrlTtl();
        return new FileLink(storage.presignedGet(key, ttl), filename(exam, kind), Instant.now().plus(ttl));
    }
    public Download download(UUID userId, UUID examId, FileKind kind) {
        var exam = coordinator.owned(userId, examId); var key = readyKey(exam, kind);
        return new Download(storage.get(key), filename(exam, kind));
    }
    private String readyKey(MockExam exam, FileKind kind) {
        if (exam.getStatus() != TaskStatus.SUCCEEDED) throw new BusinessException(HttpStatus.CONFLICT,
                "MOCK_EXAM_FILE_NOT_READY", "模拟卷文件尚未就绪");
        var key = kind == FileKind.PAPER ? exam.getPaperObjectKey() : exam.getAnswerObjectKey();
        if (key == null) throw new BusinessException(HttpStatus.CONFLICT, "MOCK_EXAM_FILE_NOT_READY", "模拟卷文件尚未就绪");
        return key;
    }
    private String filename(MockExam exam, FileKind kind) {
        var safe = exam.getDisplayName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (safe.isBlank()) safe = "模拟卷";
        var time = java.time.ZonedDateTime.ofInstant(exam.getCreatedAt(), java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        return safe + "_" + time + "_" + (kind == FileKind.PAPER ? "试卷" : "参考答案") + ".pdf";
    }
    public record Accepted(UUID mockExamId, UUID taskId, TaskProgressService.TaskView task, boolean idempotentReplay) {}
    public record Detail(MockExamCoordinator.Summary summary, MockExamRequestNormalizer.Normalized request,
                         UUID knowledgeVersionId, UUID taskId, boolean filesReady) {}
    public enum FileKind { PAPER, ANSWER }
    public record FileLink(String url, String filename, Instant expiresAt) {}
    public record Download(InputStream input, String filename) {}
}
