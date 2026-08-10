package com.finalweek.upload;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UploadCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(UploadCleanupScheduler.class);
    private final UploadStateStore states;
    private final ObjectStorage storage;

    public UploadCleanupScheduler(UploadStateStore states, ObjectStorage storage) {
        this.states = states; this.storage = storage;
    }

    @Scheduled(fixedDelayString = "${finalweek.storage.cleanup-interval}")
    public void cleanExpiredUploads() {
        for (var uploadId : states.expired(Instant.now(), 100)) {
            try {
                storage.deletePrefix("uploads/" + uploadId + "/");
                states.removeExpired(uploadId);
            } catch (RuntimeException exception) {
                log.warn("Failed to clean expired upload uploadId={}", uploadId, exception);
            }
        }
    }
}
