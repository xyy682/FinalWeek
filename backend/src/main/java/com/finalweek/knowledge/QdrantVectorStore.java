package com.finalweek.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class QdrantVectorStore {
    private final String endpoint;
    private final String collection;
    private final int dimensions;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean initialized = new AtomicBoolean();

    public QdrantVectorStore(FinalWeekProperties properties, ObjectMapper mapper) {
        var config = properties.retrieval();
        this.endpoint = config.qdrantEndpoint().replaceAll("/$", "");
        this.collection = config.qdrantCollection();
        this.dimensions = config.embeddingDimensions();
        this.mapper = mapper;
        if (!collection.matches("[A-Za-z0-9_-]{1,120}")) throw new IllegalArgumentException("Qdrant collection 名称不合法");
    }

    public void upsert(List<VectorPoint> points) {
        ensureCollection();
        for (int offset = 0; offset < points.size(); offset += 64) {
            var batch = points.subList(offset, Math.min(offset + 64, points.size()));
            var values = new ArrayList<Map<String, Object>>();
            for (var point : batch) {
                values.add(Map.of("id", point.segmentId().toString(), "vector", point.vector(), "payload", Map.of(
                        "userId", point.userId().toString(), "courseId", point.courseId().toString(),
                        "materialId", point.materialId().toString(), "segmentId", point.segmentId().toString())));
            }
            send("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", values));
        }
    }

    public List<UUID> query(UUID userId, UUID courseId, float[] vector, int limit) {
        ensureCollection();
        var filter = filter(Map.of("userId", userId.toString(), "courseId", courseId.toString()));
        var response = send("POST", "/collections/" + collection + "/points/query",
                Map.of("query", vector, "filter", filter, "limit", limit, "with_payload", false));
        var result = response.path("result");
        if (result.has("points")) result = result.path("points");
        var ids = new ArrayList<UUID>();
        if (result.isArray()) for (var point : result) {
            try { ids.add(UUID.fromString(point.path("id").asText())); } catch (IllegalArgumentException ignored) {}
        }
        return List.copyOf(ids);
    }

    public void deleteMaterial(UUID materialId) {
        deleteBy(Map.of("materialId", materialId.toString()));
    }

    public void deleteCourse(UUID courseId) {
        deleteBy(Map.of("courseId", courseId.toString()));
    }

    private void deleteBy(Map<String, String> fields) {
        ensureCollection();
        send("POST", "/collections/" + collection + "/points/delete?wait=true", Map.of("filter", filter(fields)));
    }

    private Map<String, Object> filter(Map<String, String> fields) {
        var conditions = new ArrayList<Map<String, Object>>();
        fields.forEach((key, value) -> conditions.add(Map.of("key", key, "match", Map.of("value", value))));
        return Map.of("must", conditions);
    }

    private void ensureCollection() {
        if (initialized.get()) return;
        synchronized (initialized) {
            if (initialized.get()) return;
            var request = HttpRequest.newBuilder(uri("/collections/" + collection)).timeout(Duration.ofSeconds(15)).GET().build();
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    send("PUT", "/collections/" + collection,
                            Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")));
                } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    int actual = mapper.readTree(response.body()).path("result").path("config")
                            .path("params").path("vectors").path("size").asInt();
                    if (actual != dimensions) throw new PermanentTaskException("QDRANT_DIMENSION_MISMATCH",
                            "Qdrant collection 维度与 EMBEDDING_DIMENSIONS 不一致");
                } else {
                    throw status(response.statusCode(), "Qdrant collection 检查失败");
                }
                for (var field : List.of("userId", "courseId", "materialId", "segmentId")) {
                    send("PUT", "/collections/" + collection + "/index?wait=true",
                            Map.of("field_name", field, "field_schema", "keyword"));
                }
                initialized.set(true);
            } catch (PermanentTaskException | RetryableTaskException exception) { throw exception; }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RetryableTaskException("QDRANT_INTERRUPTED", "Qdrant 调用被中断");
            } catch (Exception exception) {
                throw new RetryableTaskException("QDRANT_UNAVAILABLE", "Qdrant 暂时不可用");
            }
        }
    }

    private JsonNode send(String method, String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            var publisher = HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
            var request = switch (method) {
                case "POST" -> builder.POST(publisher).build();
                case "PUT" -> builder.PUT(publisher).build();
                default -> throw new IllegalArgumentException("Unsupported method");
            };
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw status(response.statusCode(), "Qdrant 请求失败");
            return mapper.readTree(response.body());
        } catch (PermanentTaskException | RetryableTaskException exception) { throw exception; }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableTaskException("QDRANT_INTERRUPTED", "Qdrant 调用被中断");
        } catch (Exception exception) {
            throw new RetryableTaskException("QDRANT_UNAVAILABLE", "Qdrant 暂时不可用");
        }
    }

    private RuntimeException status(int status, String message) {
        return status >= 500 || status == 429
                ? new RetryableTaskException("QDRANT_UNAVAILABLE", message + "（HTTP " + status + "）")
                : new PermanentTaskException("QDRANT_REQUEST_REJECTED", message + "（HTTP " + status + "）");
    }

    private URI uri(String path) { return URI.create(endpoint + path); }
}
