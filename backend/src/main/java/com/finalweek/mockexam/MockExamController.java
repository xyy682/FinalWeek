package com.finalweek.mockexam;

import com.finalweek.auth.FinalWeekPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MockExamController {
    private final MockExamService service;
    public MockExamController(MockExamService service) { this.service = service; }

    @PostMapping("/courses/{courseId}/mock-exams") @ResponseStatus(HttpStatus.ACCEPTED)
    MockExamService.Accepted create(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID courseId,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody Request input) {
        return service.create(principal.userId(), courseId, key, input.toDomain());
    }
    @GetMapping("/courses/{courseId}/mock-exams")
    MockExamCoordinator.PageView page(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                      @PathVariable UUID courseId, @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(required = false) Integer size) {
        return service.page(principal.userId(), courseId, page, size);
    }
    @GetMapping("/mock-exams/{examId}")
    MockExamService.Detail detail(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID examId) {
        return service.detail(principal.userId(), examId);
    }
    @PostMapping("/mock-exams/{examId}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    MockExamService.Accepted retry(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID examId,
                                   @RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody Request input) {
        return service.retry(principal.userId(), examId, key, input.toDomain());
    }
    @DeleteMapping("/mock-exams/{examId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID examId) {
        service.delete(principal.userId(), examId);
    }
    @GetMapping("/mock-exams/{examId}/files/{kind}/preview")
    MockExamService.FileLink preview(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                     @PathVariable UUID examId, @PathVariable String kind) {
        return service.file(principal.userId(), examId, fileKind(kind));
    }
    @GetMapping(value = "/mock-exams/{examId}/files/{kind}/download", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<org.springframework.core.io.InputStreamResource> download(
            @AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID examId,
            @PathVariable String kind) {
        var value = service.download(principal.userId(), examId, fileKind(kind));
        var disposition = ContentDisposition.attachment().filename(value.filename(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new org.springframework.core.io.InputStreamResource(value.input()));
    }
    private MockExamService.FileKind fileKind(String value) { return switch (value) {
        case "paper" -> MockExamService.FileKind.PAPER; case "answer" -> MockExamService.FileKind.ANSWER;
        default -> throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
    }; }

    record Request(@Size(max = 120) String displayName, @NotNull MockExamScope scope,
                   List<UUID> outlineNodeIds, @NotNull Map<MockExamQuestionType, Integer> questionCounts,
                   @NotNull ScoreMode scoreMode, Map<MockExamQuestionType, Integer> scorePerQuestion,
                   @Min(1) @Max(1000) Integer totalScore, @Min(1) @Max(300) Integer durationMinutes,
                   Boolean allowGeneralKnowledge, @Size(max = 2000) String instructions) {
        MockExamRequestNormalizer.Request toDomain() { return new MockExamRequestNormalizer.Request(displayName, scope,
                outlineNodeIds, questionCounts, scoreMode, scorePerQuestion, totalScore, durationMinutes,
                allowGeneralKnowledge, instructions); }
    }
}
