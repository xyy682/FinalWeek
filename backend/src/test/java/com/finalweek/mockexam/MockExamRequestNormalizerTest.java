package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.*;
import com.finalweek.common.api.BusinessException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class MockExamRequestNormalizerTest {
    private final MockExamRequestNormalizer normalizer = new MockExamRequestNormalizer(
            new MockExamProperties(3, Duration.ofMinutes(4), 50, 1000, 300, 2000, 10,
                    .82, "v1", 8));

    @Test void supportsAllSevenTypesAndComputesCustomScore() {
        var counts = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
        var scores = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
        Arrays.stream(MockExamQuestionType.values()).forEach(type -> { counts.put(type, 1); scores.put(type, 5); });
        var value = normalizer.normalize(new MockExamRequestNormalizer.Request("期末", MockExamScope.WHOLE_COURSE,
                List.of(), counts, ScoreMode.CUSTOM, scores, 35, 90, false, "中文作答"), List.of());
        assertThat(value.questionCount()).isEqualTo(7); assertThat(value.scoreSum()).isEqualTo(35);
        assertThat(value.questionCounts()).containsOnlyKeys(MockExamQuestionType.values());
    }

    @Test void rejectsQuestionOverflowAndStructuredInstructionConflict() {
        assertThatThrownBy(() -> normalizer.normalize(request(Map.of(MockExamQuestionType.ESSAY, 51), ""), List.of()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> normalizer.normalize(request(Map.of(MockExamQuestionType.ESSAY, 1), "请联网搜索最新资料"), List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("MOCK_EXAM_INSTRUCTION_CONFLICT"));
    }

    @Test void customTotalMustExactlyMatch() {
        var input = new MockExamRequestNormalizer.Request(null, MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 2), ScoreMode.CUSTOM,
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 3), 10, null, true, null);
        assertThatThrownBy(() -> normalizer.normalize(input, List.of())).isInstanceOf(BusinessException.class);
    }

    @Test void hugeCountsAndScoresCannotWrapAroundIntegerLimits() {
        assertThatThrownBy(() -> normalizer.normalize(request(
                Map.of(MockExamQuestionType.SINGLE_CHOICE, Integer.MAX_VALUE), ""), List.of()))
                .isInstanceOf(BusinessException.class);
        var hugeScores = new MockExamRequestNormalizer.Request(null, MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 2), ScoreMode.CUSTOM,
                Map.of(MockExamQuestionType.SINGLE_CHOICE, Integer.MAX_VALUE), null, null, true, null);
        assertThatThrownBy(() -> normalizer.normalize(hugeScores, List.of()))
                .isInstanceOf(BusinessException.class);
    }
    private MockExamRequestNormalizer.Request request(Map<MockExamQuestionType, Integer> counts, String instruction) {
        return new MockExamRequestNormalizer.Request(null, MockExamScope.WHOLE_COURSE, List.of(), counts,
                ScoreMode.AUTO, Map.of(), null, null, true, instruction);
    }
}
