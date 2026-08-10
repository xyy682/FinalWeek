package com.finalweek.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserAccountRepository repository;

    public UserAccountService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserAccount findOrCreate(String normalizedEmail) {
        return repository.findByEmail(normalizedEmail)
                .orElseGet(() -> repository.save(new UserAccount(normalizedEmail)));
    }
}
