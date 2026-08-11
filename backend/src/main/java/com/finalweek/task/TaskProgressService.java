package com.finalweek.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TaskProgressService {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final TaskProperties properties;
    private final Map<UUID, Map<UUID, SseEmitter>> emitters = new ConcurrentHashMap<>();

    public TaskProgressService(StringRedisTemplate redis, ObjectMapper mapper, TaskProperties properties) {
        this.redis = redis; this.mapper = mapper; this.properties = properties;
    }

    public SseEmitter subscribe(UUID userId) {
        var emitter = new SseEmitter(0L);
        var emitterId = UUID.randomUUID();
        emitters.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>()).put(emitterId, emitter);
        Runnable cleanup = () -> remove(userId, emitterId);
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(ignored -> cleanup.run());
        try { emitter.send(SseEmitter.event().name("connected").data(Map.of("at", Instant.now()))); }
        catch (IOException exception) { cleanup.run(); }
        return emitter;
    }

    public void publish(ParseTask task) {
        var progress = TaskView.from(task);
        try {
            redis.opsForValue().set(key(task.getId()), mapper.writeValueAsString(progress), properties.progressTtl());
        } catch (JsonProcessingException | RuntimeException ignored) { /* MySQL remains the source of truth. */ }
        var userEmitters = emitters.get(task.getUserId());
        if (userEmitters == null) return;
        userEmitters.forEach((id, emitter) -> {
            try { emitter.send(SseEmitter.event().name("task-progress").id(task.getUpdatedAt().toString())
                    .data(TaskEvent.from(task))); }
            catch (IOException exception) { remove(task.getUserId(), id); }
        });
    }
    public record TaskEvent(String eventId, UUID taskId, TaskStatus status, TaskStage stage,
                            int progress, String message, Instant occurredAt) {
        static TaskEvent from(ParseTask task) {
            int percentage = switch (task.getCurrentStage() == null ? TaskStage.UPLOADED : task.getCurrentStage()) {
                case UPLOADED -> 5; case CONTENT_EXTRACTED -> 35; case CHUNKED -> 60;
                case EMBEDDING_COMPLETED -> 90; case CONTEXT_RETRIEVED -> 35;
                case OUTLINE_GENERATED -> 80; case COMPLETED -> 100;
            };
            return new TaskEvent(task.getUpdatedAt() + ":" + task.getId(), task.getId(), task.getStatus(),
                    task.getCurrentStage(), percentage, task.getErrorMessage(), task.getUpdatedAt());
        }
    }

    private void remove(UUID userId, UUID emitterId) {
        var values = emitters.get(userId);
        if (values != null) { values.remove(emitterId); if (values.isEmpty()) emitters.remove(userId); }
    }
    private String key(UUID taskId) { return "fw:task:progress:" + taskId; }

    public record TaskView(UUID id, UUID courseId, UUID materialId, TaskType type, TaskStatus status,
                           TaskStage currentStage, int publishAttemptCount, int deliveryAttemptCount,
                           int apiAttemptCount, int manualRetryCount, int executionRound,
                           String errorCode, String errorMessage, Instant updatedAt) {
        public static TaskView from(ParseTask task) {
            return new TaskView(task.getId(), task.getCourseId(), task.getMaterialId(), task.getTaskType(),
                    task.getStatus(), task.getCurrentStage(), task.getPublishAttemptCount(),
                    task.getDeliveryAttemptCount(), task.getApiAttemptCount(), task.getManualRetryCount(),
                    task.getExecutionRound(), task.getErrorCode(), task.getErrorMessage(), task.getUpdatedAt());
        }
    }
}
