package com.finalweek.auth;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import java.time.Instant;
import java.util.UUID;

public class UserAccount {

    private UUID id;

    private String email;

    private String status = "ACTIVE";

    private Instant createdAt;

    private Instant updatedAt;

    protected UserAccount() {}

    public UserAccount(String email) {
        this.email = email;
    }

    @BeforeInsert
    void created() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @BeforeUpdate
    void updated() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }
}

