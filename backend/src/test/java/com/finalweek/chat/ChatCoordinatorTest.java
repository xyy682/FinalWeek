package com.finalweek.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.finalweek.course.CourseRepository;
import com.finalweek.task.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatCoordinatorTest {
    @Test
    void failedQuestionRetryReusesOriginalMessage() {
        var messages = mock(ChatMessageRepository.class); var tasks = mock(BackgroundTaskRepository.class);
        var question = mock(ChatMessage.class); var userId = UUID.randomUUID(); var messageId = UUID.randomUUID();
        when(messages.findOwnedForUpdate(messageId, userId)).thenReturn(Optional.of(question));
        when(question.getRole()).thenReturn(ChatRole.USER); when(question.getStatus()).thenReturn(ChatMessageStatus.FAILED);

        new ChatCoordinator(mock(CourseRepository.class), messages, tasks).retryForTask(userId, messageId);

        verify(question).retry(); verify(messages).save(question);
    }

    @Test
    void succeededQuestionReturnsExistingAnswerAndSameTask() {
        var messages = mock(ChatMessageRepository.class); var tasks = mock(BackgroundTaskRepository.class);
        var question = mock(ChatMessage.class); var answer = mock(ChatMessage.class); var task = mock(BackgroundTask.class);
        var userId = UUID.randomUUID(); var messageId = UUID.randomUUID(); var taskId = UUID.randomUUID();
        when(messages.findByIdAndUserId(messageId, userId)).thenReturn(Optional.of(question));
        when(question.getRole()).thenReturn(ChatRole.USER); when(question.getStatus()).thenReturn(ChatMessageStatus.SUCCEEDED);
        when(question.getId()).thenReturn(messageId); when(question.getBackgroundTaskId()).thenReturn(taskId);
        when(messages.findByReplyToId(messageId)).thenReturn(Optional.of(answer)); when(tasks.findById(taskId)).thenReturn(Optional.of(task));

        var result = new ChatCoordinator(mock(CourseRepository.class), messages, tasks).retryStart(userId, messageId);

        assertThat(result.answer()).isSameAs(answer); assertThat(result.task()).isSameAs(task);
    }
}
