package com.finalweek.chat;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select message from ChatMessage message where message.id = :id and message.userId = :userId")
    Optional<ChatMessage> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
    Optional<ChatMessage> findByReplyToId(UUID replyToId);
    Optional<ChatMessage> findByIdAndUserId(UUID id, UUID userId);
    Optional<ChatMessage> findByIdAndUserIdAndCourseId(UUID id, UUID userId, UUID courseId);
    @Query("select message from ChatMessage message where message.courseId = :courseId and message.userId = :userId " +
            "and (:before is null or message.createdAt < :before) order by message.createdAt desc, message.id desc")
    List<ChatMessage> page(@Param("userId") UUID userId, @Param("courseId") UUID courseId,
                           @Param("before") Instant before, Pageable pageable);
    @Query("select message from ChatMessage message where message.courseId = :courseId and message.userId = :userId " +
            "and message.status = 'SUCCEEDED' and message.createdAt < :before " +
            "order by message.createdAt desc, message.id desc")
    List<ChatMessage> recentSucceeded(@Param("userId") UUID userId, @Param("courseId") UUID courseId,
                                      @Param("before") Instant before, Pageable pageable);
}
