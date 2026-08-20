package com.finalweek.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.CourseService;
import com.finalweek.task.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {
    private final CourseService courses;
    private final ChatMessageRepository messages;
    private final ChatCoordinator coordinator;
    private final TaskRateLimiter rateLimiter;
    private final TaskDispatchService dispatcher;
    private final FinalWeekProperties properties;
    private final ObjectMapper mapper;
    public ChatService(CourseService courses, ChatMessageRepository messages, ChatCoordinator coordinator,
                       TaskRateLimiter rateLimiter, TaskDispatchService dispatcher,
                       FinalWeekProperties properties, ObjectMapper mapper) {
        this.courses = courses; this.messages = messages; this.coordinator = coordinator;
        this.rateLimiter = rateLimiter; this.dispatcher = dispatcher; this.properties = properties; this.mapper = mapper;
    }

    public Accepted ask(UUID userId, UUID courseId, String rawQuestion) {
        var question = normalize(rawQuestion);
        rateLimiter.acquireChat(userId);
        var created = coordinator.create(userId, courseId, question);
        dispatcher.dispatchAfterRateLimit(created.task());
        return new Accepted(view(created.question()), null, TaskProgressService.TaskView.from(created.task()), false);
    }

    public Accepted retry(UUID userId, UUID messageId) {
        var start = coordinator.retryStart(userId, messageId);
        if (start.answer() != null) return new Accepted(view(start.question()), view(start.answer()),
                TaskProgressService.TaskView.from(start.task()), true);
        var task = start.task().getStatus() == TaskStatus.PUBLISH_FAILED
                ? dispatcher.republish(userId, start.task().getId())
                : dispatcher.manualRetry(userId, start.task().getId());
        return new Accepted(view(start.question()), null, TaskProgressService.TaskView.from(task), false);
    }

    @Transactional(readOnly = true)
    public MessagePage page(UUID userId, UUID courseId, UUID cursor) {
        courses.get(userId, courseId);
        var before = cursor == null ? null : messages.findByIdAndUserIdAndCourseId(cursor, userId, courseId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "CHAT_CURSOR_INVALID", "聊天游标无效"))
                .getCreatedAt();
        int limit = Math.max(1, properties.ai().chatHistoryLimit());
        var raw = messages.page(userId, courseId, before, PageRequest.of(0, limit + 1));
        var hasMore = raw.size() > limit;
        var selected = new ArrayList<>(raw.subList(0, Math.min(limit, raw.size())));
        var next = hasMore && !selected.isEmpty() ? selected.get(selected.size() - 1).getId() : null;
        Collections.reverse(selected);
        return new MessagePage(selected.stream().map(this::view).toList(), next);
    }

    MessageView view(ChatMessage value) {
        try {
            var refs = mapper.readValue(value.getSourceRefsJson(), new TypeReference<List<ChatSourceRef>>() {});
            return new MessageView(value.getId(), value.getRole(), value.getContent(), refs,
                    value.isGeneralKnowledgeUsed(), value.getGeneralKnowledgeContent(), value.getStatus(),
                    value.getReplyToId(), value.getErrorCode(), value.getCreatedAt());
        } catch (Exception exception) { throw new IllegalStateException("聊天来源数据损坏", exception); }
    }
    private String normalize(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "CHAT_QUESTION_REQUIRED", "问题不能为空");
        var normalized = value.strip();
        if (normalized.length() > 2_000) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "CHAT_QUESTION_TOO_LONG", "问题不能超过 2000 个字符");
        return normalized;
    }
    public record Accepted(MessageView question, MessageView answer, TaskProgressService.TaskView task,
                           boolean idempotentReplay) {}
    public record MessagePage(List<MessageView> messages, UUID nextCursor) {}
    public record MessageView(UUID id, ChatRole role, String content, List<ChatSourceRef> sources,
                              boolean generalKnowledgeUsed, String generalKnowledgeContent,
                              ChatMessageStatus status, UUID replyToId, String errorCode,
                              java.time.Instant createdAt) {}
}
