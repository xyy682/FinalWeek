package com.finalweek.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finalweek")
public record FinalWeekProperties(
        Limits limits,
        Retrieval retrieval,
        Ai ai) {

    public record Limits(
            int courseLimit,
            Duration uploadTtl,
            int documentMaxSizeMb,
            int mediaMaxSizeMb,
            Duration mediaMaxDuration,
            int parseUserRatePerMinute,
            int parseGlobalRatePerMinute,
            int planUserRatePerMinute,
            int chatUserRatePerMinute) {}

    public record Retrieval(int vectorTopK, int bm25TopK, int finalTopK, int rrfK) {}

    public record Ai(
            Duration planRequestTimeout,
            Duration chatRequestTimeout,
            int chatHistoryLimit,
            String asrModel,
            String ocrModel,
            String embeddingModel,
            String llmModel) {}
}

