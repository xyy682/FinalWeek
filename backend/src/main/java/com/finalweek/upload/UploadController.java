package com.finalweek.upload;

import com.finalweek.auth.FinalWeekPrincipal;
import com.finalweek.material.MaterialController.MaterialResponse;
import com.finalweek.material.MaterialType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class UploadController {
    private final UploadService service;
    public UploadController(UploadService service) { this.service = service; }

    @PostMapping("/courses/{courseId}/uploads/init")
    @ResponseStatus(HttpStatus.CREATED)
    InitResponse initialize(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID courseId,
                            @Valid @RequestBody InitRequest request) {
        var metadata = service.initialize(principal.userId(), courseId, request.filename(), request.fileSize(),
                request.sha256(), request.materialType(), request.focusNotes());
        return new InitResponse(metadata.uploadId(), metadata.chunkSize(), metadata.totalChunks(), metadata.expiresAt());
    }

    @GetMapping("/uploads/{uploadId}")
    StatusResponse status(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID uploadId) {
        var status = service.status(principal.userId(), uploadId);
        return new StatusResponse(status.uploadId(), status.status(), status.uploadedChunks(),
                status.materialId(), status.expiresAt());
    }

    @PutMapping(value = "/uploads/{uploadId}/chunks/{chunkIndex}", consumes = "application/octet-stream")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void putChunk(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID uploadId,
                  @PathVariable int chunkIndex, HttpServletRequest request) throws IOException {
        service.putChunk(principal.userId(), uploadId, chunkIndex, request.getInputStream(), request.getContentLengthLong());
    }

    @PostMapping("/uploads/{uploadId}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CompleteResponse complete(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID uploadId) {
        var result = service.complete(principal.userId(), uploadId);
        return new CompleteResponse(MaterialResponse.from(result.material(), result.task().getId()),
                result.task().getId(), result.duplicate());
    }

    record InitRequest(@NotBlank @Size(max = 255) String filename, @Positive long fileSize,
                       @Pattern(regexp = "(?i)[0-9a-f]{64}") String sha256,
                       @NotNull MaterialType materialType, @Size(max = 1000) String focusNotes) {}
    record InitResponse(UUID uploadId, long chunkSize, int totalChunks, Instant expiresAt) {}
    record StatusResponse(UUID uploadId, String status, List<Integer> uploadedChunks, UUID materialId, Instant expiresAt) {}
    record CompleteResponse(MaterialResponse material, UUID taskId, boolean duplicate) {}
}
