package com.finalweek.knowledgeversion;

import com.finalweek.auth.FinalWeekPrincipal;
import com.finalweek.task.TaskProgressService.TaskView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/courses/{courseId}")
public class KnowledgeVersionController {
    private final KnowledgeVersionService service;
    public KnowledgeVersionController(KnowledgeVersionService service) { this.service = service; }

    @PostMapping("/knowledge-versions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ConfirmationResponse confirm(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID courseId,
                                 @RequestBody(required = false) ConfirmRequest request) {
        var result = service.confirm(principal.userId(), courseId,
                request != null && request.ignoreFailedMaterials());
        return new ConfirmationResponse(KnowledgeVersionService.VersionView.from(result.version()),
                TaskView.from(result.task()), result.ignoredMaterials());
    }

    @GetMapping("/knowledge-version")
    KnowledgeVersionService.StatusView status(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                               @PathVariable UUID courseId) {
        return service.status(principal.userId(), courseId);
    }
    record ConfirmRequest(boolean ignoreFailedMaterials) {}
    record ConfirmationResponse(KnowledgeVersionService.VersionView version, TaskView task,
                                List<String> ignoredMaterials) {}
}
