package com.finalweek.common.api;

import java.time.Instant;

public record ApiError(String code, String message, String requestId, Instant occurredAt) {

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, requestId, Instant.now());
    }
}

