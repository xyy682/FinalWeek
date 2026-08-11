package com.finalweek.chat;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.api.BusinessException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatAnswerValidatorTest {
    private final ChatAnswerValidator validator = new ChatAnswerValidator(new ObjectMapper());

    @Test
    void acceptsStructuredAnswerAndNormalizesGeneralKnowledge() {
        var segment = UUID.randomUUID();
        var json = """
                {"answer":"课程答案","sourceSegmentIds":["%s"],"generalKnowledgeSupplement":"  额外常识  "}
                """.formatted(segment);
        var result = validator.parse(json, Set.of(segment));
        assertThat(result.sourceSegmentIds()).containsExactly(segment);
        assertThat(result.generalKnowledgeSupplement()).isEqualTo("额外常识");
    }

    @Test
    void rejectsCitationOutsideRetrievedContext() {
        var json = """
                {"answer":"伪造答案","sourceSegmentIds":["%s"],"generalKnowledgeSupplement":null}
                """.formatted(UUID.randomUUID());
        assertThatThrownBy(() -> validator.parse(json, Set.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CHAT_ANSWER_INVALID"));
    }
}
