package com.finalweek.common.config;

import java.time.Duration;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finalweek")
public record FinalWeekProperties(
        Auth auth,
        Limits limits,
        Retrieval retrieval,
        Ai ai) {

    public record Auth(Duration codeTtl, Duration sendCooldown, Duration rateWindow, int maxSendsPerWindow,
                       int maxVerifyAttempts) {}

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

    public record Retrieval(
            int vectorTopK,
            int bm25TopK,
            int finalTopK,
            int rrfK,
            int chunkMaxTokens,
            int chunkOverlapTokens,
            int embeddingBatchSize,
            int embeddingDimensions,
            String qdrantEndpoint,
            String qdrantCollection,
            Path luceneIndexPath) {}

    public record Ai(
            String endpoint,
            String apiKey,
            int maxAttempts,
            Duration planRequestTimeout,
            Duration chatRequestTimeout,
            int chatHistoryLimit,
            String asrModel,
            String ocrModel,
            String embeddingModel,
            String llmModel) {}
}
