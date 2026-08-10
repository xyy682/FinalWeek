package com.finalweek.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

public record FinalWeekPrincipal(UUID userId, String email) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}

