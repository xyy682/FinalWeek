package com.finalweek.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.task.ParseTaskRepository;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BailianLlmClient implements LlmClient {
    private final FinalWeekProperties properties;
    private final ParseTaskRepository tasks;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public BailianLlmClient(FinalWeekProperties properties, ParseTaskRepository tasks, ObjectMapper mapper) {
        this.properties = properties; this.tasks = tasks; this.mapper = mapper;
    }

    @Override public String generateJson(UUID taskId, String systemPrompt, String userPrompt) {
        var ai = properties.ai();
        if (ai.apiKey() == null || ai.apiKey().isBlank()) throw new PermanentTaskException(
                "AI_CONFIG_MISSING", "提纲生成需要配置 BAILIAN_API_KEY");
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ai.maxAttempts(); attempt++) {
            tasks.incrementApiAttempt(taskId);
            try {
                var body = mapper.writeValueAsString(Map.of(
                        "model", ai.llmModel(),
                        "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)),
                        "response_format", Map.of("type", "json_object"),
                        "enable_thinking", false));
                var request = HttpRequest.newBuilder(endpoint(ai.endpoint())).timeout(ai.outlineRequestTimeout())
                        .header("Authorization", "Bearer " + ai.apiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var content = mapper.readTree(response.body()).path("choices").path(0)
                            .path("message").path("content").asText();
                    if (content.isBlank()) throw new IllegalStateException("LLM 返回空内容");
                    return content;
                }
                if (response.statusCode() < 500 && response.statusCode() != 429) throw new PermanentTaskException(
                        "LLM_REQUEST_REJECTED", "提纲模型请求被拒绝（HTTP " + response.statusCode() + "）");
                last = new RetryableTaskException("LLM_TEMPORARY_FAILURE", "提纲模型暂时不可用");
            } catch (PermanentTaskException exception) { throw exception; }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RetryableTaskException("LLM_INTERRUPTED", "提纲模型调用被中断");
            } catch (Exception exception) {
                last = new RetryableTaskException("LLM_TEMPORARY_FAILURE", "提纲模型调用失败");
            }
        }
        throw last == null ? new RetryableTaskException("LLM_TEMPORARY_FAILURE", "提纲模型调用失败") : last;
    }

    private URI endpoint(String configured) {
        var base = configured.replaceAll("/$", "");
        return URI.create(base.endsWith("/compatible-mode/v1") ? base + "/chat/completions"
                : base + "/compatible-mode/v1/chat/completions");
    }
}
