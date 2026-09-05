package com.finalweek.task;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finalweek.task")
public record TaskProperties(String exchange, String routingKey, String queue, String deadLetterExchange,
                             String deadLetterQueue, Duration confirmTimeout, Duration stalePendingThreshold,
                             Duration staleScanInterval, Duration processingLease, Duration leaseHeartbeatInterval,
                             Duration progressTtl, int maxDeliveryAttempts) {}
