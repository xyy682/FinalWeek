package com.finalweek.ai;

import java.util.UUID;

public interface LlmClient {
    String generateJson(UUID taskId, String systemPrompt, String userPrompt);
}
