package com.finalweek.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ChatAnswerValidator {
    private final ObjectMapper mapper;
    public ChatAnswerValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public GeneratedChatAnswer parse(String json, Set<UUID> allowedSegmentIds) {
        final GeneratedChatAnswer value;
        try { value = mapper.readValue(json, GeneratedChatAnswer.class); }
        catch (Exception exception) { throw invalid("问答模型返回的 JSON 格式不正确"); }
        if (value.answer() == null || value.answer().isBlank() || value.answer().length() > 20_000) {
            throw invalid("回答正文为空或过长");
        }
        if (value.sourceSegmentIds() == null) throw invalid("回答缺少来源数组");
        var unique = new LinkedHashSet<>(value.sourceSegmentIds());
        if (unique.size() != value.sourceSegmentIds().size() || !allowedSegmentIds.containsAll(unique)) {
            throw invalid("回答引用不属于本次检索上下文");
        }
        var general = value.generalKnowledgeSupplement();
        if (general != null && general.length() > 10_000) throw invalid("通用知识补充过长");
        return new GeneratedChatAnswer(stripInternalRefs(value.answer()), List.copyOf(unique),
                general == null || general.isBlank() ? null : stripInternalRefs(general));
    }
    private String stripInternalRefs(String value) {
        return value.replaceAll("(?i)\\s*\\[[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}]", "").strip();
    }
    private BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_GATEWAY,
            "CHAT_ANSWER_INVALID", message); }
}
