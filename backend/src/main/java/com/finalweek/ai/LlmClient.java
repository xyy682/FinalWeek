package com.finalweek.ai;

import java.time.Duration;
import java.util.UUID;

public interface LlmClient {
    String generateJson(UUID taskId, String systemPrompt, String userPrompt);
    String generateJson(String systemPrompt, String userPrompt, Duration timeout);
}
