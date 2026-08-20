package com.finalweek.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UploadStateStore {
    private static final String EXPIRATIONS_KEY = "fw:upload:expirations";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public UploadStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void create(UploadMetadata metadata, Duration ttl) {
        try {
            redis.opsForValue().set(metaKey(metadata.uploadId()), objectMapper.writeValueAsString(metadata), ttl);
            redis.opsForZSet().add(EXPIRATIONS_KEY, metadata.uploadId().toString(), metadata.expiresAt().toEpochMilli());
            redis.opsForSet().add(courseKey(metadata.courseId()), metadata.uploadId().toString());
            redis.expire(courseKey(metadata.courseId()), ttl.plus(Duration.ofHours(1)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("上传元数据序列化失败", exception);
        }
    }

    public UploadMetadata get(UUID uploadId) {
        var json = redis.opsForValue().get(metaKey(uploadId));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, UploadMetadata.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("上传元数据损坏", exception);
        }
    }

    public void addChunk(UUID uploadId, int chunkIndex, Duration ttl) {
        redis.opsForSet().add(chunksKey(uploadId), Integer.toString(chunkIndex));
        redis.expire(chunksKey(uploadId), ttl);
    }

    public List<Integer> chunks(UUID uploadId) {
        var members = redis.opsForSet().members(chunksKey(uploadId));
        if (members == null) return List.of();
        return members.stream().map(Integer::parseInt).sorted().toList();
    }

    public UUID completed(UUID uploadId) {
        var value = redis.opsForValue().get(completedKey(uploadId));
        return value == null ? null : UUID.fromString(value);
    }

    public void complete(UUID uploadId, UUID materialId, Duration ttl) {
        var metadata = get(uploadId);
        redis.opsForValue().set(completedKey(uploadId), materialId.toString(), ttl);
        redis.delete(metaKey(uploadId));
        redis.delete(chunksKey(uploadId));
        redis.opsForZSet().remove(EXPIRATIONS_KEY, uploadId.toString());
        if (metadata != null) redis.opsForSet().remove(courseKey(metadata.courseId()), uploadId.toString());
    }

    public List<UUID> expired(Instant now, int limit) {
        var values = redis.opsForZSet().rangeByScore(EXPIRATIONS_KEY, 0, now.toEpochMilli(), 0, limit);
        if (values == null) return List.of();
        return values.stream().map(UUID::fromString).toList();
    }

    public void removeExpired(UUID uploadId) {
        var metadata = get(uploadId);
        redis.delete(metaKey(uploadId));
        redis.delete(chunksKey(uploadId));
        redis.opsForZSet().remove(EXPIRATIONS_KEY, uploadId.toString());
        if (metadata != null) redis.opsForSet().remove(courseKey(metadata.courseId()), uploadId.toString());
    }

    public int activeCount(UUID courseId) {
        var key = courseKey(courseId);
        var members = redis.opsForSet().members(key);
        if (members == null || members.isEmpty()) return 0;
        int count = 0;
        for (var value : members) {
            try {
                var uploadId = UUID.fromString(value);
                if (get(uploadId) == null) redis.opsForSet().remove(key, value);
                else count++;
            } catch (IllegalArgumentException ignored) { redis.opsForSet().remove(key, value); }
        }
        return count;
    }

    static String metaKey(UUID id) { return "fw:upload:" + id + ":meta"; }
    static String chunksKey(UUID id) { return "fw:upload:" + id + ":chunks"; }
    static String completedKey(UUID id) { return "fw:upload:" + id + ":completed"; }
    static String courseKey(UUID id) { return "fw:upload:course:" + id; }
}
