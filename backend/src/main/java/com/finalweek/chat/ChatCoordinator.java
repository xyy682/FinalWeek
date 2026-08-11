package com.finalweek.chat;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatCoordinator {
    private final CourseRepository courses;
    private final ChatMessageRepository messages;
    public ChatCoordinator(CourseRepository courses, ChatMessageRepository messages) {
        this.courses = courses; this.messages = messages;
    }

    @Transactional
    public ChatMessage create(UUID userId, UUID courseId, String question) {
        courses.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::courseNotFound);
        return messages.saveAndFlush(ChatMessage.question(userId, courseId, question));
    }

    @Transactional
    public RetryStart retry(UUID userId, UUID messageId) {
        var question = messages.findOwnedForUpdate(messageId, userId).orElseThrow(this::messageNotFound);
        if (question.getRole() != ChatRole.USER) throw messageNotFound();
        if (question.getStatus() == ChatMessageStatus.SUCCEEDED) {
            var answer = messages.findByReplyToId(question.getId()).orElseThrow(this::messageNotFound);
            return new RetryStart(question, answer, true);
        }
        if (question.getStatus() == ChatMessageStatus.PENDING) throw new BusinessException(HttpStatus.CONFLICT,
                "CHAT_MESSAGE_IN_PROGRESS", "该问题仍在处理中，请刷新聊天记录");
        question.retry(); messages.save(question);
        return new RetryStart(question, null, false);
    }

    @Transactional
    public ChatMessage succeed(UUID userId, UUID questionId, String answer, String refsJson, String general) {
        var question = messages.findOwnedForUpdate(questionId, userId).orElseThrow(this::messageNotFound);
        if (question.getStatus() == ChatMessageStatus.SUCCEEDED) {
            return messages.findByReplyToId(questionId).orElseThrow(this::messageNotFound);
        }
        if (question.getStatus() != ChatMessageStatus.PENDING) throw new BusinessException(HttpStatus.CONFLICT,
                "CHAT_MESSAGE_NOT_PENDING", "问题已不在处理状态");
        var response = messages.save(ChatMessage.answer(question, answer, refsJson, general));
        question.succeed(); messages.save(question);
        return response;
    }

    @Transactional
    public void fail(UUID userId, UUID questionId, String code) {
        messages.findOwnedForUpdate(questionId, userId)
                .filter(value -> value.getRole() == ChatRole.USER && value.getStatus() == ChatMessageStatus.PENDING)
                .ifPresent(value -> { value.fail(code); messages.save(value); });
    }
    private BusinessException courseNotFound() { return new BusinessException(HttpStatus.NOT_FOUND,
            "COURSE_NOT_FOUND", "课程不存在或无权访问"); }
    private BusinessException messageNotFound() { return new BusinessException(HttpStatus.NOT_FOUND,
            "CHAT_MESSAGE_NOT_FOUND", "聊天消息不存在或无权访问"); }
    public record RetryStart(ChatMessage question, ChatMessage answer, boolean replay) {}
}
