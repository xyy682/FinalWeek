package com.finalweek.material;

import com.finalweek.auth.FinalWeekPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MaterialController {
    private final MaterialService service;

    public MaterialController(MaterialService service) { this.service = service; }

    @GetMapping("/courses/{courseId}/materials")
    List<MaterialResponse> list(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID courseId) {
        return service.list(principal.userId(), courseId).stream().map(MaterialResponse::from).toList();
    }

    @GetMapping("/materials/{materialId}")
    MaterialResponse get(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID materialId) {
        return MaterialResponse.from(service.get(principal.userId(), materialId));
    }

    @DeleteMapping("/materials/{materialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID materialId) {
        service.delete(principal.userId(), materialId);
    }

    public record MaterialResponse(UUID id, UUID courseId, String originalFilename, long sizeBytes,
                                   String mediaType, MaterialType materialType, String focusNotes,
                                   MaterialStatus status, String contentHash, Instant createdAt, Instant updatedAt) {
        public static MaterialResponse from(Material material) {
            return new MaterialResponse(material.getId(), material.getCourseId(), material.getOriginalFilename(),
                    material.getSizeBytes(), material.getMediaType(), material.getMaterialType(),
                    material.getFocusNotes(), material.getStatus(), material.getContentHash(),
                    material.getCreatedAt(), material.getUpdatedAt());
        }
    }
}
