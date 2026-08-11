package com.finalweek.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.CourseService;
import com.finalweek.knowledge.HybridRetrievalService;
import com.finalweek.material.CourseSegmentRepository;
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
    private final HybridRetrievalService retrieval;
    private final CourseSegmentRepository segments;
    private final ChatAnswerValidator validator;
    private final LlmClient llm;
    private final FinalWeekProperties properties;
    private final ObjectMapper mapper;
    public ChatService(CourseService courses, ChatMessageRepository messages, ChatCoordinator coordinator,
                       TaskRateLimiter rateLimiter, HybridRetrievalService retrieval,
                       CourseSegmentRepository segments, ChatAnswerValidator validator,
                       LlmClient llm, FinalWeekProperties properties, ObjectMapper mapper) {
        this.courses = courses; this.messages = messages; this.coordinator = coordinator;
        this.rateLimiter = rateLimiter; this.retrieval = retrieval; this.segments = segments;
        this.validator = validator; this.llm = llm; this.properties = properties; this.mapper = mapper;
    }

    public Exchange ask(UUID userId, UUID courseId, String rawQuestion) {
        var question = normalize(rawQuestion);
        var saved = coordinator.create(userId, courseId, question);
        return process(userId, saved);
    }

    public Exchange retry(UUID userId, UUID messageId) {
        var start = coordinator.retry(userId, messageId);
        if (start.replay()) return new Exchange(view(start.question()), view(start.answer()), true);
        return process(userId, start.question());
    }

    private Exchange process(UUID userId, ChatMessage question) {
        try {
            rateLimiter.acquireChat(userId);
            var result = retrieval.retrieve(userId, question.getCourseId(), question.getContent());
            if (result.hits().isEmpty()) {
                var answer = coordinator.succeed(userId, question.getId(),
                        "当前课程资料不足以回答这个问题。请补充相关资料，或换一种更贴近资料内容的问法。", "[]", null);
                return new Exchange(view(questionAfterSuccess(question)), view(answer), false);
            }
            var allowed = new LinkedHashSet<UUID>();
            result.hits().forEach(hit -> allowed.add(hit.segment().getId()));
            var history = recent(userId, question);
            var json = llm.generateJson(systemPrompt(), userPrompt(question.getContent(), history, result.hits()),
                    properties.ai().chatRequestTimeout());
            var generated = validator.parse(json, allowed);
            var owned = segments.findRetrievableByIds(generated.sourceSegmentIds(), userId, question.getCourseId());
            if (owned.size() != generated.sourceSegmentIds().size()) throw new BusinessException(HttpStatus.BAD_GATEWAY,
                    "CHAT_SOURCE_INVALID", "回答引用不属于当前用户或课程");
            var byId = new HashMap<UUID, ChatSourceRef>(); owned.forEach(value -> byId.put(value.getId(), ChatSourceRef.from(value)));
            var refs = generated.sourceSegmentIds().stream().map(byId::get).toList();
            var answer = coordinator.succeed(userId, question.getId(), generated.answer(),
                    mapper.writeValueAsString(refs), generated.generalKnowledgeSupplement());
            return new Exchange(view(questionAfterSuccess(question)), view(answer), false);
        } catch (BusinessException exception) {
            coordinator.fail(userId, question.getId(), exception.code()); throw exception;
        } catch (PermanentTaskException exception) {
            coordinator.fail(userId, question.getId(), exception.code());
            throw new BusinessException(HttpStatus.BAD_GATEWAY, exception.code(), exception.getMessage());
        } catch (RetryableTaskException exception) {
            coordinator.fail(userId, question.getId(), exception.code());
            var status = exception.code().equals("LLM_TIMEOUT") ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            throw new BusinessException(status, exception.code(), exception.getMessage());
        } catch (Exception exception) {
            coordinator.fail(userId, question.getId(), "CHAT_GENERATION_FAILED");
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "CHAT_GENERATION_FAILED", "回答生成失败，请稍后重试");
        }
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

    private List<ChatMessage> recent(UUID userId, ChatMessage question) {
        var values = new ArrayList<>(messages.recentSucceeded(userId, question.getCourseId(), question.getCreatedAt(),
                PageRequest.of(0, Math.max(1, properties.ai().chatHistoryLimit()))));
        Collections.reverse(values); return values;
    }
    private String systemPrompt() {
        return "你是课程问答助手。只返回 JSON；回答正文只能陈述课程上下文支持的内容。引用放入 sourceSegmentIds，正文不要显示 segment ID。若资料不足要明确说明。任何模型常识必须仅放入 generalKnowledgeSupplement，不得伪装成课程资料。";
    }
    private String userPrompt(String question, List<ChatMessage> history,
                              List<com.finalweek.knowledge.RetrievalHit> hits) {
        var historyText = new StringBuilder();
        history.forEach(value -> historyText.append(value.getRole()).append(": ")
                .append(value.getContent().replace("\n", " ")).append('\n'));
        var context = new StringBuilder();
        hits.forEach(hit -> context.append("segmentId=").append(hit.segment().getId()).append("\n")
                .append(hit.segment().getContent()).append("\n---\n"));
        return """
                输出结构：{"answer":"基于课程资料的 Markdown 子集正文","sourceSegmentIds":["UUID"],"generalKnowledgeSupplement":"可选的通用知识补充或 null"}
                最近对话：
                %s
                当前问题：%s
                本次课程上下文：
                %s
                sourceSegmentIds 只能引用上面的 segmentId。不要联网，不要虚构来源。
                """.formatted(historyText, question, context);
    }
    private String normalize(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "CHAT_QUESTION_REQUIRED", "问题不能为空");
        var normalized = value.strip();
        if (normalized.length() > 2_000) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "CHAT_QUESTION_TOO_LONG", "问题不能超过 2000 个字符");
        return normalized;
    }
    private ChatMessage questionAfterSuccess(ChatMessage value) { value.succeed(); return value; }
    private MessageView view(ChatMessage value) {
        try {
            var refs = mapper.readValue(value.getSourceRefsJson(), new TypeReference<List<ChatSourceRef>>() {});
            return new MessageView(value.getId(), value.getRole(), value.getContent(), refs,
                    value.isGeneralKnowledgeUsed(), value.getGeneralKnowledgeContent(), value.getStatus(),
                    value.getReplyToId(), value.getErrorCode(), value.getCreatedAt());
        } catch (Exception exception) { throw new IllegalStateException("聊天来源数据损坏", exception); }
    }
    public record Exchange(MessageView question, MessageView answer, boolean idempotentReplay) {}
    public record MessagePage(List<MessageView> messages, UUID nextCursor) {}
    public record MessageView(UUID id, ChatRole role, String content, List<ChatSourceRef> sources,
                              boolean generalKnowledgeUsed, String generalKnowledgeContent,
                              ChatMessageStatus status, UUID replyToId, String errorCode,
                              java.time.Instant createdAt) {}
}
