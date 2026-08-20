package com.finalweek.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.task.BackgroundTaskRepository;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BailianOcrClient implements OcrClient {
    private final FinalWeekProperties properties;
    private final BackgroundTaskRepository tasks;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public BailianOcrClient(FinalWeekProperties properties, BackgroundTaskRepository tasks, ObjectMapper mapper) {
        this.properties = properties; this.tasks = tasks; this.mapper = mapper;
    }
    @Override public String recognize(UUID taskId, Path image) {
        var ai = properties.ai();
        if (ai.apiKey() == null || ai.apiKey().isBlank()) throw new PermanentTaskException(
                "AI_CONFIG_MISSING", "扫描内容需要 OCR，但未配置 BAILIAN_API_KEY");
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ai.maxAttempts(); attempt++) {
            tasks.incrementApiAttempt(taskId);
            try {
                var encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(image));
                var content = List.of(
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + encoded)),
                        Map.of("type", "text", "text", "仅输出图像中可见的原始文字，不要解释、总结或补充。"));
                var body = mapper.writeValueAsString(Map.of("model", ai.ocrModel(),
                        "messages", List.of(Map.of("role", "user", "content", content))));
                var endpoint = ai.endpoint().replaceAll("/$", "") + "/compatible-mode/v1/chat/completions";
                var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + ai.apiKey()).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return mapper.readTree(response.body()).path("choices").path(0).path("message")
                            .path("content").asText("").strip();
                }
                if (response.statusCode() < 500 && response.statusCode() != 429) throw new PermanentTaskException(
                        "OCR_REQUEST_REJECTED", "OCR 请求被拒绝（HTTP " + response.statusCode() + "）");
                last = new RetryableTaskException("OCR_TEMPORARY_FAILURE", "OCR 服务暂时不可用");
            } catch (PermanentTaskException exception) { throw exception; }
            catch (Exception exception) { last = new RetryableTaskException("OCR_TEMPORARY_FAILURE", "OCR 调用失败"); }
        }
        throw last == null ? new RetryableTaskException("OCR_TEMPORARY_FAILURE", "OCR 调用失败") : last;
    }
}
