package com.finalweek.mockexam;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finalweek.mock-exam")
public record MockExamProperties(int userRatePerMinute, Duration requestTimeout, int maxQuestions,
                                 int maxScore, int maxDurationMinutes, int maxInstructionLength,
                                 int defaultPageSize, double historySimilarityWarningThreshold,
                                 String qualityPolicyVersion, int cleanupMaxAttempts) {
    public MockExamProperties {
        if (userRatePerMinute < 1 || requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || maxQuestions < 1 || maxScore < 1 || maxDurationMinutes < 1 || maxInstructionLength < 1
                || defaultPageSize < 1 || cleanupMaxAttempts < 1)
            throw new IllegalArgumentException("模拟卷数值配置必须为正数");
        if (!Double.isFinite(historySimilarityWarningThreshold)
                || historySimilarityWarningThreshold < 0 || historySimilarityWarningThreshold > 1)
            throw new IllegalArgumentException("历史题相似度阈值必须在 0–1 之间");
        if (qualityPolicyVersion == null || qualityPolicyVersion.isBlank() || qualityPolicyVersion.length() > 30)
            throw new IllegalArgumentException("模拟卷质量策略版本必须为 1–30 位非空文本");
    }
}
