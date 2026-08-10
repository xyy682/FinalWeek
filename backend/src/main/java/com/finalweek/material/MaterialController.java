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
import com.finalweek.task.ParseTaskRepository;

@RestController
@RequestMapping("/api/v1")
public class MaterialController {
    private final MaterialService service;
    private final ParseTaskRepository tasks;

    public MaterialController(MaterialService service, ParseTaskRepository tasks) { this.service = service; this.tasks = tasks; }

    @GetMapping("/courses/{courseId}/materials")
    List<MaterialResponse> list(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID courseId) {
        return service.list(principal.userId(), courseId).stream().map(material -> MaterialResponse.from(material,
                tasks.findByMaterial_Id(material.getId()).map(task -> task.getId()).orElse(null))).toList();
    }

    @GetMapping("/materials/{materialId}")
    MaterialResponse get(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID materialId) {
        var material = service.get(principal.userId(), materialId);
        return MaterialResponse.from(material, tasks.findByMaterial_Id(materialId).map(task -> task.getId()).orElse(null));
    }

    @DeleteMapping("/materials/{materialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID materialId) {
        service.delete(principal.userId(), materialId);
    }

    public record MaterialResponse(UUID id, UUID courseId, UUID taskId, String originalFilename, long sizeBytes,
                                   String mediaType, MaterialType materialType, String focusNotes,
                                   MaterialStatus status, String contentHash, Instant createdAt, Instant updatedAt) {
        public static MaterialResponse from(Material material, UUID taskId) {
            return new MaterialResponse(material.getId(), material.getCourseId(), taskId, material.getOriginalFilename(),
                    material.getSizeBytes(), material.getMediaType(), material.getMaterialType(),
                    material.getFocusNotes(), material.getStatus(), material.getContentHash(),
                    material.getCreatedAt(), material.getUpdatedAt());
        }
        public static MaterialResponse from(Material material) { return from(material, null); }
    }
}
