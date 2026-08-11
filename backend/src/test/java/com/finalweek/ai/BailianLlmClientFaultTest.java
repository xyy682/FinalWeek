package com.finalweek.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.task.ParseTaskRepository;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BailianLlmClientFaultTest {
    @Test
    void retriesThirdParty429And5xxWithinBudgetThenReportsRetryableFailure() throws Exception {
        for (var status : new int[] {429, 503}) {
            var requests = new AtomicInteger(); var server = server(status, requests);
            try {
                var tasks = mock(ParseTaskRepository.class); var taskId = UUID.randomUUID();
                var client = new BailianLlmClient(properties(server, 3), tasks, new ObjectMapper(), HttpClient.newHttpClient());
                assertThatThrownBy(() -> client.generateJson(taskId, "system", "user"))
                        .isInstanceOf(RetryableTaskException.class);
                org.assertj.core.api.Assertions.assertThat(requests.get()).isEqualTo(3);
                verify(tasks, times(3)).incrementApiAttempt(taskId);
            } finally { server.stop(0); }
        }
    }

    @Test
    void doesNotRetryPermanentThirdParty4xx() throws Exception {
        var requests = new AtomicInteger(); var server = server(400, requests);
        try {
            var client = new BailianLlmClient(properties(server, 3), mock(ParseTaskRepository.class),
                    new ObjectMapper(), HttpClient.newHttpClient());
            assertThatThrownBy(() -> client.generateJson(UUID.randomUUID(), "system", "user"))
                    .isInstanceOf(PermanentTaskException.class);
            org.assertj.core.api.Assertions.assertThat(requests.get()).isOne();
        } finally { server.stop(0); }
    }

    private HttpServer server(int status, AtomicInteger requests) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            var body = "{\"error\":\"injected\"}".getBytes(); exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }); server.start(); return server;
    }
    private FinalWeekProperties properties(HttpServer server, int attempts) {
        return new FinalWeekProperties(null, null, null, new FinalWeekProperties.Ai(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", attempts,
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), 20,
                "asr", "ocr", "embedding", "llm"));
    }
}
