package com.finalweek.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.course.CourseService;
import com.finalweek.task.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatServiceTest {
    @Test
    void askPersistsPendingMessageAndReturnsAcceptedTask() {
        var fixture = fixture(); var question = question(fixture); var task = task(fixture, question);
        when(fixture.coordinator.create(fixture.userId, fixture.courseId, "问题"))
                .thenReturn(new ChatCoordinator.Created(question, task));

        var result = fixture.service.ask(fixture.userId, fixture.courseId, " 问题 ");

        assertThat(result.answer()).isNull(); assertThat(result.question().status()).isEqualTo(ChatMessageStatus.PENDING);
        assertThat(result.task().type()).isEqualTo(TaskType.ANSWER_CHAT);
        verify(fixture.dispatcher).dispatchAfterRateLimit(task);
    }

    @Test
    void rateLimitPreventsMessageAndTaskCreation() {
        var fixture = fixture();
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_RATE_LIMITED", "limited"))
                .when(fixture.limiter).acquireChat(fixture.userId);
        assertThatThrownBy(() -> fixture.service.ask(fixture.userId, fixture.courseId, "问题"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(fixture.coordinator, fixture.dispatcher);
    }

    private ChatMessage question(Fixture fixture) {
        var value = mock(ChatMessage.class); when(value.getId()).thenReturn(UUID.randomUUID());
        when(value.getCourseId()).thenReturn(fixture.courseId); when(value.getRole()).thenReturn(ChatRole.USER);
        when(value.getStatus()).thenReturn(ChatMessageStatus.PENDING); when(value.getContent()).thenReturn("问题");
        when(value.getSourceRefsJson()).thenReturn("[]"); when(value.getCreatedAt()).thenReturn(Instant.now()); return value;
    }
    private BackgroundTask task(Fixture fixture, ChatMessage question) {
        return new BackgroundTask(fixture.userId, fixture.courseId, TaskType.ANSWER_CHAT, question.getId(), false);
    }
    private Fixture fixture() {
        var courses = mock(CourseService.class); var messages = mock(ChatMessageRepository.class);
        var coordinator = mock(ChatCoordinator.class); var limiter = mock(TaskRateLimiter.class);
        var dispatcher = mock(TaskDispatchService.class); var properties = mock(FinalWeekProperties.class);
        var ai = mock(FinalWeekProperties.Ai.class); when(properties.ai()).thenReturn(ai); when(ai.chatHistoryLimit()).thenReturn(20);
        var service = new ChatService(courses, messages, coordinator, limiter, dispatcher, properties, new ObjectMapper());
        return new Fixture(service, coordinator, limiter, dispatcher, UUID.randomUUID(), UUID.randomUUID());
    }
    private record Fixture(ChatService service, ChatCoordinator coordinator, TaskRateLimiter limiter,
                           TaskDispatchService dispatcher, UUID userId, UUID courseId) {}
}
