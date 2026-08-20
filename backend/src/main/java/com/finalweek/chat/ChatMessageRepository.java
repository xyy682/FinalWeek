package com.finalweek.chat;

import com.finalweek.common.persistence.BaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface ChatMessageRepository extends BaseRepository<ChatMessage> {
    @Select("select * from chat_message where id = #{id} and user_id = #{userId} for update")
    Optional<ChatMessage> findOwnedForUpdate(UUID id, UUID userId);
    @Select("select * from chat_message where reply_to_id = #{replyToId}")
    Optional<ChatMessage> findByReplyToId(UUID replyToId);
    @Select("select * from chat_message where id = #{id} and user_id = #{userId}")
    Optional<ChatMessage> findByIdAndUserId(UUID id, UUID userId);
    @Select("select * from chat_message where id = #{id} and user_id = #{userId} and course_id = #{courseId}")
    Optional<ChatMessage> findByIdAndUserIdAndCourseId(UUID id, UUID userId, UUID courseId);
    @Select({"<script>", "select * from chat_message where course_id = #{courseId} and user_id = #{userId}",
            "<if test='before != null'> and created_at &lt; #{before}</if>",
            "order by created_at desc, id desc limit #{limit}", "</script>"})
    List<ChatMessage> page(UUID userId, UUID courseId, Instant before, int limit);
    @Select("select * from chat_message where course_id = #{courseId} and user_id = #{userId} " +
            "and status = 'SUCCEEDED' and created_at &lt; #{before} order by created_at desc, id desc limit #{limit}")
    List<ChatMessage> recentSucceeded(UUID userId, UUID courseId, Instant before, int limit);
}
