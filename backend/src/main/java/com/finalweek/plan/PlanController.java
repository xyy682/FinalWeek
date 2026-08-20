package com.finalweek.plan;

import com.finalweek.auth.FinalWeekPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PlanController {
    private final PlanService service;
    public PlanController(PlanService service) { this.service = service; }

    @GetMapping("/courses/{courseId}/plan")
    PlanResponse get(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID courseId) {
        return new PlanResponse(service.get(principal.userId(), courseId));
    }
    @PostMapping("/courses/{courseId}/plan/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PlanService.GenerateResult generate(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                        @PathVariable UUID courseId,
                                        @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody GenerateRequest request) {
        return service.generate(principal.userId(), courseId, key, new PlanRequestCoordinator.PlanInput(
                request.examDate(), request.dailyMinutes(), request.masteryLevel(), request.targetScore()));
    }
    @PatchMapping("/plan-tasks/{taskId}/completed")
    PlanService.TaskView completed(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                   @PathVariable UUID taskId, @Valid @RequestBody CompletedRequest request) {
        return service.setCompleted(principal.userId(), taskId, request.completed());
    }
    record GenerateRequest(@NotNull @Future LocalDate examDate,
                           @Min(1) @Max(1440) int dailyMinutes,
                           @NotNull MasteryLevel masteryLevel,
                           @Min(1) @Max(100) int targetScore) {}
    record CompletedRequest(@NotNull Boolean completed) {}
    record PlanResponse(PlanService.PlanView plan) {}
}
