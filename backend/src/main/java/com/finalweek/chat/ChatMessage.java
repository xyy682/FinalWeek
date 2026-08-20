package com.finalweek.chat;

import com.finalweek.task.BackgroundTask;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "chat_message")
public class ChatMessage {
    @Id @UuidGenerator private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "course_id", nullable = false) private UUID courseId;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "background_task_id", unique = true)
    private BackgroundTask backgroundTask;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private ChatRole role;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String content;
    @Column(name = "source_refs_json", nullable = false, columnDefinition = "json") private String sourceRefsJson;
    @Column(name = "general_knowledge_used", nullable = false) private boolean generalKnowledgeUsed;
    @Lob @Column(name = "general_knowledge_content", columnDefinition = "LONGTEXT")
    private String generalKnowledgeContent;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private ChatMessageStatus status;
    @Column(name = "reply_to_id", unique = true) private UUID replyToId;
    @Column(name = "error_code", length = 80) private String errorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
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
    @PrePersist void created() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void updated() { updatedAt = Instant.now(); }
    public void succeed() { status = ChatMessageStatus.SUCCEEDED; errorCode = null; }
    public void fail(String code) { status = ChatMessageStatus.FAILED; errorCode = code; }
    public void retry() { status = ChatMessageStatus.PENDING; errorCode = null; }
    public void attachTask(BackgroundTask task) { this.backgroundTask = task; }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseId() { return courseId; }
    public UUID getBackgroundTaskId() { return backgroundTask == null ? null : backgroundTask.getId(); }
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
