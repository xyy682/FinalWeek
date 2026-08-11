package com.finalweek.chat;

import com.finalweek.auth.FinalWeekPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ChatController {
    private final ChatService service;
    public ChatController(ChatService service) { this.service = service; }

    @GetMapping("/courses/{courseId}/messages")
    ChatService.MessagePage page(@AuthenticationPrincipal FinalWeekPrincipal principal,
                                 @PathVariable UUID courseId, @RequestParam(required = false) UUID cursor) {
        return service.page(principal.userId(), courseId, cursor);
    }
    @PostMapping("/courses/{courseId}/messages")
    ChatService.Exchange ask(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID courseId,
                             @Valid @RequestBody AskRequest request) {
        return service.ask(principal.userId(), courseId, request.question());
    }
    @PostMapping("/chat-messages/{messageId}/retry")
    ChatService.Exchange retry(@AuthenticationPrincipal FinalWeekPrincipal principal, @PathVariable UUID messageId) {
        return service.retry(principal.userId(), messageId);
    }
    record AskRequest(@NotBlank @Size(max = 2000) String question) {}
}
