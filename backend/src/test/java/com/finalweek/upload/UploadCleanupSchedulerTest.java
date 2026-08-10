package com.finalweek.upload;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadCleanupSchedulerTest {
    @Test
    void removesMinioPrefixBeforeDroppingExpiredRedisState() {
        var states = mock(UploadStateStore.class);
        var storage = mock(ObjectStorage.class);
        var uploadId = UUID.randomUUID();
        when(states.expired(org.mockito.ArgumentMatchers.any(Instant.class), org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(List.of(uploadId));

        new UploadCleanupScheduler(states, storage).cleanExpiredUploads();

        var order = inOrder(storage, states);
        order.verify(storage).deletePrefix("uploads/" + uploadId + "/");
        order.verify(states).removeExpired(uploadId);
    }
}
