package com.finalweek.upload;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadCompletionRepository extends JpaRepository<UploadCompletion, UUID> {
}
