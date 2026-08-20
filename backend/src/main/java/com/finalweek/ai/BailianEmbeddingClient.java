package com.finalweek.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.task.BackgroundTaskRepository;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BailianEmbeddingClient implements EmbeddingClient {
    private final FinalWeekProperties properties;
    private final BackgroundTaskRepository tasks;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public BailianEmbeddingClient(FinalWeekProperties properties, BackgroundTaskRepository tasks, ObjectMapper mapper) {
        this.properties = properties; this.tasks = tasks; this.mapper = mapper;
    }

    @Override public List<float[]> embedDocuments(UUID taskId, List<String> texts) {
        return embed(taskId, texts);
    }

    @Override public float[] embedQuery(String query) {
        return embed(null, List.of(query)).getFirst();
    }

    private List<float[]> embed(UUID taskId, List<String> texts) {
        var ai = properties.ai(); var retrieval = properties.retrieval();
        if (texts.isEmpty()) return List.of();
        if (texts.size() > retrieval.embeddingBatchSize()) throw new IllegalArgumentException("Embedding batch 超过配置上限");
        if (ai.apiKey() == null || ai.apiKey().isBlank()) throw new PermanentTaskException(
                "AI_CONFIG_MISSING", "Embedding 需要配置 BAILIAN_API_KEY");
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ai.maxAttempts(); attempt++) {
            if (taskId != null) tasks.incrementApiAttempt(taskId);
            try {
                var body = mapper.writeValueAsString(Map.of("model", ai.embeddingModel(), "input", texts,
                        "dimensions", retrieval.embeddingDimensions(), "encoding_format", "float"));
                var request = HttpRequest.newBuilder(embeddingEndpoint(ai.endpoint())).timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + ai.apiKey()).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return vectors(response.body(), texts.size());
                if (response.statusCode() < 500 && response.statusCode() != 429) throw new PermanentTaskException(
                        "EMBEDDING_REQUEST_REJECTED", "Embedding 请求被拒绝（HTTP " + response.statusCode() + "）");
                last = new RetryableTaskException("EMBEDDING_TEMPORARY_FAILURE", "Embedding 服务暂时不可用");
            } catch (PermanentTaskException exception) { throw exception; }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RetryableTaskException("EMBEDDING_INTERRUPTED", "Embedding 调用被中断");
            } catch (Exception exception) {
                last = new RetryableTaskException("EMBEDDING_TEMPORARY_FAILURE", "Embedding 调用失败");
            }
        }
        throw last == null ? new RetryableTaskException("EMBEDDING_TEMPORARY_FAILURE", "Embedding 调用失败") : last;
    }

    private URI embeddingEndpoint(String configured) {
        var base = configured.replaceAll("/$", "");
        return URI.create(base.endsWith("/compatible-mode/v1") ? base + "/embeddings"
                : base + "/compatible-mode/v1/embeddings");
    }

    private List<float[]> vectors(String body, int expected) throws Exception {
        var nodes = new ArrayList<JsonNode>();
        mapper.readTree(body).path("data").forEach(nodes::add);
        nodes.sort(Comparator.comparingInt(node -> node.path("index").asInt()));
        if (nodes.size() != expected) throw new IllegalStateException("Embedding 返回数量不一致");
        var result = new ArrayList<float[]>();
        for (var node : nodes) {
            var values = node.path("embedding");
            if (values.size() != properties.retrieval().embeddingDimensions()) {
                throw new IllegalStateException("Embedding 返回维度不一致");
            }
            var vector = new float[values.size()];
            for (int index = 0; index < values.size(); index++) vector[index] = (float) values.get(index).asDouble();
            result.add(vector);
        }
        return List.copyOf(result);
    }
}
