package com.finalweek.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.finalweek.course.CourseRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatCoordinatorTest {
    @Test
    void retryOfFailedQuestionReusesOriginalMessage() {
        var courses = mock(CourseRepository.class); var messages = mock(ChatMessageRepository.class);
        var question = mock(ChatMessage.class); var userId = UUID.randomUUID(); var messageId = UUID.randomUUID();
        when(messages.findOwnedForUpdate(messageId, userId)).thenReturn(Optional.of(question));
        when(question.getRole()).thenReturn(ChatRole.USER); when(question.getStatus()).thenReturn(ChatMessageStatus.FAILED);
        when(messages.save(question)).thenReturn(question);

        var result = new ChatCoordinator(courses, messages).retry(userId, messageId);

        assertThat(result.replay()).isFalse(); assertThat(result.question()).isSameAs(question);
        verify(question).retry(); verify(messages).save(question);
    }

    @Test
    void retryOfSucceededQuestionReturnsExistingAssistant() {
        var courses = mock(CourseRepository.class); var messages = mock(ChatMessageRepository.class);
        var question = mock(ChatMessage.class); var answer = mock(ChatMessage.class);
        var userId = UUID.randomUUID(); var messageId = UUID.randomUUID();
        when(messages.findOwnedForUpdate(messageId, userId)).thenReturn(Optional.of(question));
        when(question.getRole()).thenReturn(ChatRole.USER); when(question.getStatus()).thenReturn(ChatMessageStatus.SUCCEEDED);
        when(question.getId()).thenReturn(messageId); when(messages.findByReplyToId(messageId)).thenReturn(Optional.of(answer));

        var result = new ChatCoordinator(courses, messages).retry(userId, messageId);

        assertThat(result.replay()).isTrue(); assertThat(result.answer()).isSameAs(answer);
        verify(question, never()).retry(); verify(messages, never()).save(any());
    }
}
