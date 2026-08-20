package com.finalweek.outline;

import com.finalweek.auth.FinalWeekPrincipal;
import com.finalweek.task.TaskProgressService.TaskView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OutlineController {
    private final OutlineService service;
    public OutlineController(OutlineService service) { this.service = service; }

    @GetMapping("/courses/{courseId}/outline")
    OutlineResponse get(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID courseId) {
        var page = service.get(principal.userId(), courseId);
        return new OutlineResponse(page.outline(), page.activeTask() == null ? null : TaskView.from(page.activeTask()));
    }
    @PatchMapping("/outline-nodes/{nodeId}/importance")
    OutlineService.NodeView importance(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID nodeId,
                                       @Valid @RequestBody ImportanceRequest request) {
        return service.adjustImportance(principal.userId(), nodeId, request.importance());
    }
    record ImportanceRequest(@NotNull OutlineImportance importance) {}
    record OutlineResponse(OutlineService.OutlineView outline, TaskView activeTask) {}
}
