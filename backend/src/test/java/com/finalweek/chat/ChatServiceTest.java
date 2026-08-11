package com.finalweek.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.ai.LlmClient;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.CourseService;
import com.finalweek.knowledge.*;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.material.CourseSegment;
import com.finalweek.task.TaskRateLimiter;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatServiceTest {
    @Test
    void rateLimitKeepsQuestionFailedAndNeverCallsRetrievalOrModel() {
        var fixture = fixture(); var question = message(ChatRole.USER, ChatMessageStatus.PENDING, "问题");
        when(question.getCourseId()).thenReturn(fixture.courseId);
        when(fixture.coordinator.create(fixture.userId, fixture.courseId, "问题")).thenReturn(question);
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_RATE_LIMITED", "limited"))
                .when(fixture.limiter).acquireChat(fixture.userId);

        assertThatThrownBy(() -> fixture.service.ask(fixture.userId, fixture.courseId, "问题"))
                .isInstanceOf(BusinessException.class);

        verify(fixture.coordinator).fail(fixture.userId, question.getId(), "CHAT_RATE_LIMITED");
        verifyNoInteractions(fixture.retrieval, fixture.llm);
    }

    @Test
    void emptyRetrievalReturnsExplicitInsufficientAnswerWithoutModel() {
        var fixture = fixture(); var question = message(ChatRole.USER, ChatMessageStatus.PENDING, "不存在的内容");
        when(question.getCourseId()).thenReturn(fixture.courseId);
        var answer = message(ChatRole.ASSISTANT, ChatMessageStatus.SUCCEEDED, "资料不足");
        when(fixture.coordinator.create(fixture.userId, fixture.courseId, "不存在的内容")).thenReturn(question);
        when(fixture.retrieval.retrieve(fixture.userId, fixture.courseId, "不存在的内容"))
                .thenReturn(new HybridRetrievalResult(List.of(), false, false));
        var questionId = question.getId();
        when(fixture.coordinator.succeed(eq(fixture.userId), eq(questionId), contains("资料不足"), eq("[]"), isNull()))
                .thenReturn(answer);

        var result = fixture.service.ask(fixture.userId, fixture.courseId, "不存在的内容");

        assertThat(result.answer().sources()).isEmpty();
        verifyNoInteractions(fixture.llm);
    }

    @Test
    void synchronousTimeoutMarksOriginalQuestionFailedAndReturnsGatewayTimeout() {
        var fixture = fixture(); var question = message(ChatRole.USER, ChatMessageStatus.PENDING, "课程问题");
        when(question.getCourseId()).thenReturn(fixture.courseId);
        when(fixture.coordinator.create(fixture.userId, fixture.courseId, "课程问题")).thenReturn(question);
        var segment = mock(CourseSegment.class); when(segment.getId()).thenReturn(UUID.randomUUID());
        when(segment.getContent()).thenReturn("课程证据");
        when(fixture.retrieval.retrieve(fixture.userId, fixture.courseId, "课程问题"))
                .thenReturn(new HybridRetrievalResult(List.of(new RetrievalHit(segment, 1, 1, 1)), false, false));
        when(fixture.llm.generateJson(anyString(), anyString(), any())).thenThrow(
                new com.finalweek.task.RetryableTaskException("LLM_TIMEOUT", "timeout"));

        assertThatThrownBy(() -> fixture.service.ask(fixture.userId, fixture.courseId, "课程问题"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
        verify(fixture.coordinator).fail(fixture.userId, question.getId(), "LLM_TIMEOUT");
    }

    private ChatMessage message(ChatRole role, ChatMessageStatus status, String content) {
        var value = mock(ChatMessage.class); when(value.getId()).thenReturn(UUID.randomUUID());
        when(value.getCourseId()).thenReturn(UUID.randomUUID()); when(value.getRole()).thenReturn(role);
        when(value.getStatus()).thenReturn(status); when(value.getContent()).thenReturn(content);
        when(value.getSourceRefsJson()).thenReturn("[]"); when(value.getCreatedAt()).thenReturn(Instant.now());
        return value;
    }
    private Fixture fixture() {
        var courses = mock(CourseService.class); var messages = mock(ChatMessageRepository.class);
        var coordinator = mock(ChatCoordinator.class); var limiter = mock(TaskRateLimiter.class);
        var retrieval = mock(HybridRetrievalService.class); var segments = mock(CourseSegmentRepository.class);
        var validator = mock(ChatAnswerValidator.class); var llm = mock(LlmClient.class);
        var properties = mock(FinalWeekProperties.class); var ai = mock(FinalWeekProperties.Ai.class);
        when(properties.ai()).thenReturn(ai); when(ai.chatHistoryLimit()).thenReturn(20);
        when(ai.chatRequestTimeout()).thenReturn(java.time.Duration.ofSeconds(60));
        var service = new ChatService(courses, messages, coordinator, limiter, retrieval, segments, validator,
                llm, properties, new ObjectMapper());
        return new Fixture(service, coordinator, limiter, retrieval, llm, UUID.randomUUID(), UUID.randomUUID());
    }
    private record Fixture(ChatService service, ChatCoordinator coordinator, TaskRateLimiter limiter,
                           HybridRetrievalService retrieval, LlmClient llm, UUID userId, UUID courseId) {}
}
