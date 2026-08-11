package com.finalweek.upload;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("finalweek.storage")
public record StorageProperties(
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        DataSize chunkSize,
        Duration cleanupInterval,
        Duration previewUrlTtl) {
}
