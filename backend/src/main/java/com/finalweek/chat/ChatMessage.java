package com.finalweek.chat;

import com.finalweek.common.persistence.BeforeInsert;
import com.finalweek.common.persistence.BeforeUpdate;

import com.finalweek.task.BackgroundTask;
import java.time.Instant;
import java.util.UUID;

public class ChatMessage {
    private UUID id;
    private UUID userId;
    private UUID courseId;
    private UUID backgroundTaskId;
    private ChatRole role;
    private String content;
    private String sourceRefsJson;
    private boolean generalKnowledgeUsed;
    private String generalKnowledgeContent;
    private ChatMessageStatus status;
    private UUID replyToId;
    private String errorCode;
    private Instant createdAt;
    private Instant updatedAt;
    protected ChatMessage() {}
    public static ChatMessage question(UUID userId, UUID courseId, String content) {
        var value = new ChatMessage(); value.userId = userId; value.courseId = courseId;
        value.role = ChatRole.USER; value.content = content; value.sourceRefsJson = "[]";
        value.status = ChatMessageStatus.PENDING; return value;
    }
    public static ChatMessage answer(ChatMessage question, String content, String refs, String generalKnowledgeContent) {
        var value = new ChatMessage(); value.userId = question.userId; value.courseId = question.courseId;
        value.role = ChatRole.ASSISTANT; value.content = content; value.sourceRefsJson = refs;
        value.generalKnowledgeContent = generalKnowledgeContent;
        value.generalKnowledgeUsed = generalKnowledgeContent != null && !generalKnowledgeContent.isBlank();
        value.status = ChatMessageStatus.SUCCEEDED;
        value.replyToId = question.id; return value;
    }
    @BeforeInsert void created() { createdAt = Instant.now(); updatedAt = createdAt; }
    @BeforeUpdate void updated() { updatedAt = Instant.now(); }
    public void succeed() { status = ChatMessageStatus.SUCCEEDED; errorCode = null; }
    public void fail(String code) { status = ChatMessageStatus.FAILED; errorCode = code; }
    public void retry() { status = ChatMessageStatus.PENDING; errorCode = null; }
    public void attachTask(BackgroundTask task) { this.backgroundTaskId = task.getId(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getBackgroundTaskId() { return backgroundTaskId; }
    public ChatRole getRole() { return role; }
    public String getContent() { return content; }
    public String getSourceRefsJson() { return sourceRefsJson; }
    public boolean isGeneralKnowledgeUsed() { return generalKnowledgeUsed; }
    public String getGeneralKnowledgeContent() { return generalKnowledgeContent; }
    public ChatMessageStatus getStatus() { return status; }
    public UUID getReplyToId() { return replyToId; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
