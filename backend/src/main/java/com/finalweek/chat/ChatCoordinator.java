package com.finalweek.chat;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseRepository;
import com.finalweek.task.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatCoordinator {
    private final CourseRepository courses;
    private final ChatMessageRepository messages;
    private final BackgroundTaskRepository tasks;
    public ChatCoordinator(CourseRepository courses, ChatMessageRepository messages,
                           BackgroundTaskRepository tasks) {
        this.courses = courses; this.messages = messages; this.tasks = tasks;
    }

    @Transactional
    public Created create(UUID userId, UUID courseId, String question) {
        courses.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::courseNotFound);
        var message = messages.saveAndFlush(ChatMessage.question(userId, courseId, question));
        var task = tasks.saveAndFlush(new BackgroundTask(userId, courseId, TaskType.ANSWER_CHAT,
                message.getId(), false));
        message.attachTask(task); messages.saveAndFlush(message);
        return new Created(message, task);
    }

    @Transactional(readOnly = true)
    public RetryStart retryStart(UUID userId, UUID messageId) {
        var question = messages.findByIdAndUserId(messageId, userId).orElseThrow(this::messageNotFound);
        if (question.getRole() != ChatRole.USER) throw messageNotFound();
        var answer = question.getStatus() == ChatMessageStatus.SUCCEEDED
                ? messages.findByReplyToId(question.getId()).orElseThrow(this::messageNotFound) : null;
        var task = question.getBackgroundTaskId() == null ? null : tasks.findById(question.getBackgroundTaskId()).orElse(null);
        if (task == null) throw new BusinessException(HttpStatus.CONFLICT, "CHAT_LEGACY_MESSAGE",
                "旧版问答消息不能作为后台任务恢复，请重新提问");
        return new RetryStart(question, answer, task);
    }

    @Transactional
    public ChatMessage succeed(UUID userId, UUID questionId, String answer, String refsJson, String general) {
        var question = messages.findOwnedForUpdate(questionId, userId).orElseThrow(this::messageNotFound);
        if (question.getStatus() == ChatMessageStatus.SUCCEEDED)
            return messages.findByReplyToId(questionId).orElseThrow(this::messageNotFound);
        if (question.getStatus() != ChatMessageStatus.PENDING) throw new PermanentTaskException(
                "CHAT_MESSAGE_NOT_PENDING", "问题已不在处理状态");
        var response = messages.save(ChatMessage.answer(question, answer, refsJson, general));
        question.succeed(); messages.save(question); return response;
    }

    @Transactional
    public void fail(UUID userId, UUID questionId, String code) {
        messages.findOwnedForUpdate(questionId, userId)
                .filter(value -> value.getRole() == ChatRole.USER && value.getStatus() == ChatMessageStatus.PENDING)
                .ifPresent(value -> { value.fail(code); messages.save(value); });
    }

    @Transactional
    public void retryForTask(UUID userId, UUID questionId) {
        var question = messages.findOwnedForUpdate(questionId, userId).orElseThrow(this::messageNotFound);
        if (question.getRole() != ChatRole.USER || question.getStatus() != ChatMessageStatus.FAILED)
            throw new BusinessException(HttpStatus.CONFLICT, "CHAT_MESSAGE_NOT_RETRYABLE", "仅失败的问题可以重试");
        question.retry(); messages.save(question);
    }
    private BusinessException courseNotFound() { return new BusinessException(HttpStatus.NOT_FOUND,
            "COURSE_NOT_FOUND", "课程不存在或无权访问"); }
    private BusinessException messageNotFound() { return new BusinessException(HttpStatus.NOT_FOUND,
            "CHAT_MESSAGE_NOT_FOUND", "聊天消息不存在或无权访问"); }
    public record Created(ChatMessage question, BackgroundTask task) {}
    public record RetryStart(ChatMessage question, ChatMessage answer, BackgroundTask task) {}
}
