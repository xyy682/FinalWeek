package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.task.PermanentTaskException;
import java.util.*;
import org.junit.jupiter.api.Test;

class MockExamValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockExamValidator validator = new MockExamValidator(mapper, new MockExamFormulaValidator(), new MockExamScoreAllocator());

    @Test void validatesCourseQuestionAndGeneralKnowledgeBoundary() throws Exception {
        var segmentId = UUID.randomUUID(); var request = request(true);
        var courseQuestion = question(false, List.of(segmentId));
        var generalQuestion = question(true, List.of());
        var exam = new GeneratedMockExam(List.of(courseQuestion, generalQuestion));
        assertThat(validator.parse(mapper.writeValueAsString(exam), request, Set.of(segmentId)).questions()).hasSize(2);
    }

    @Test void strictModeRejectsGeneralKnowledgeAndCrossVersionSource() throws Exception {
        var segmentId = UUID.randomUUID();
        assertThatThrownBy(() -> validator.parse(mapper.writeValueAsString(
                new GeneratedMockExam(List.of(question(false, List.of(segmentId)), question(true, List.of())))),
                request(false), Set.of(segmentId))).isInstanceOf(PermanentTaskException.class);
        assertThatThrownBy(() -> validator.parse(mapper.writeValueAsString(
                new GeneratedMockExam(List.of(question(false, List.of(UUID.randomUUID())), question(false, List.of(segmentId))))),
                request(false), Set.of(segmentId))).isInstanceOfSatisfying(PermanentTaskException.class,
                error -> assertThat(error.code()).isEqualTo("MOCK_EXAM_SOURCE_INVALID"));
    }

    @Test void autoModeOverridesModelScoresAndFiltersUnknownCitation() throws Exception {
        var valid = UUID.randomUUID(); var unknown = UUID.randomUUID();
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 2), ScoreMode.AUTO, Map.of(), 100, null,
                false, "", 2, 100);
        var questions = List.of(question(false, List.of(valid, unknown)), question(false, List.of(valid)));
        var result = validator.parse(mapper.writeValueAsString(new GeneratedMockExam(questions)), request, Set.of(valid));
        assertThat(result.questions()).allMatch(q -> q.score() == 50);
        assertThat(result.questions()).allMatch(q -> q.sourceSegmentIds().equals(List.of(valid)));
        assertThat(result.questions().stream().mapToInt(GeneratedMockExam.Question::score).sum()).isEqualTo(100);
    }
    @Test void unsafeFormulaIsRejected() throws Exception {
        var id = UUID.randomUUID(); var q = new GeneratedMockExam.Question(MockExamQuestionType.CALCULATION,
                "计算", List.of(), new GeneratedMockExam.Answer(null, null, null, null, List.of("步骤"), "1"),
                10, false, List.of(id), List.of(new GeneratedMockExam.Formula("\\input{secret}", "stem")));
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.CALCULATION, 1), ScoreMode.CUSTOM,
                Map.of(MockExamQuestionType.CALCULATION, 10), 10, null, false, "", 1, 10);
        assertThatThrownBy(() -> validator.parse(mapper.writeValueAsString(new GeneratedMockExam(List.of(q))), request, Set.of(id)))
                .isInstanceOfSatisfying(PermanentTaskException.class,
                        error -> assertThat(error.code()).isEqualTo("MOCK_EXAM_FORMULA_UNSAFE"));
    }

    @Test void texCharacterEscapeCannotBypassFormulaCommandWhitelist() throws Exception {
        var id = UUID.randomUUID();
        var encodedInput = "^^5cinput{secret}";
        var q = new GeneratedMockExam.Question(MockExamQuestionType.CALCULATION,
                "计算", List.of(), new GeneratedMockExam.Answer(null, null, null, null, List.of("步骤"), "1"),
                10, false, List.of(id), List.of(new GeneratedMockExam.Formula(encodedInput, "stem")));
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.CALCULATION, 1), ScoreMode.CUSTOM,
                Map.of(MockExamQuestionType.CALCULATION, 10), 10, null, false, "", 1, 10);

        assertThatThrownBy(() -> validator.parse(mapper.writeValueAsString(new GeneratedMockExam(List.of(q))),
                request, Set.of(id))).isInstanceOfSatisfying(PermanentTaskException.class,
                error -> assertThat(error.code()).isEqualTo("MOCK_EXAM_FORMULA_UNSAFE"));
    }

    @Test void formulaLocationMustPreventAnswerContentFromLeakingIntoPaper() throws Exception {
        var id = UUID.randomUUID();
        var q = new GeneratedMockExam.Question(MockExamQuestionType.CALCULATION,
                "计算", List.of(), new GeneratedMockExam.Answer(null, null, null, null, List.of("步骤"), "1"),
                10, false, List.of(id), List.of(new GeneratedMockExam.Formula("x=1", "both")));
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.CALCULATION, 1), ScoreMode.CUSTOM,
                Map.of(MockExamQuestionType.CALCULATION, 10), 10, null, false, "", 1, 10);

        assertThatThrownBy(() -> validator.parse(mapper.writeValueAsString(new GeneratedMockExam(List.of(q))),
                request, Set.of(id))).isInstanceOfSatisfying(PermanentTaskException.class,
                error -> assertThat(error.code()).isEqualTo("MOCK_EXAM_FORMAT_INVALID"));
    }

    @Test void strictInsufficientResponsePreservesKnowledgeGaps() throws Exception {
        var response = new GeneratedMockExam(GeneratedMockExam.Status.INSUFFICIENT_MATERIAL,
                List.of("交流电路相量计算", "复数阻抗"), List.of());
        assertThatThrownBy(() -> validator.parse(mapper.writeValueAsString(response), request(false), Set.of()))
                .isInstanceOfSatisfying(PermanentTaskException.class, error -> {
                    assertThat(error.code()).isEqualTo("MOCK_EXAM_SOURCE_INSUFFICIENT");
                    assertThat(error.getMessage()).contains("交流电路相量计算", "复数阻抗");
                });
    }

    @Test void acceptsCompleteAnswersForAllEightQuestionTypes() throws Exception {
        var source = UUID.randomUUID(); var questions = List.of(
                typed(MockExamQuestionType.SINGLE_CHOICE, List.of("A", "B", "C", "D"), new GeneratedMockExam.Answer(List.of(0), null, null, null, null, null), source),
                typed(MockExamQuestionType.MULTIPLE_CHOICE, List.of("A", "B", "C", "D"), new GeneratedMockExam.Answer(List.of(0, 2), null, null, null, null, null), source),
                typed(MockExamQuestionType.TRUE_FALSE, List.of(), new GeneratedMockExam.Answer(null, true, null, null, null, null), source),
                typed(MockExamQuestionType.FILL_BLANK, List.of(), new GeneratedMockExam.Answer(null, null, List.of("答案"), null, null, null), source),
                typed(MockExamQuestionType.SHORT_ANSWER, List.of(), new GeneratedMockExam.Answer(null, null, null, "要点", null, null), source),
                typed(MockExamQuestionType.CALCULATION, List.of(), new GeneratedMockExam.Answer(null, null, null, null, List.of("步骤"), "结果"), source),
                typed(MockExamQuestionType.ESSAY, List.of(), new GeneratedMockExam.Answer(null, null, null, "论述", null, null), source),
                typed(MockExamQuestionType.COMPREHENSIVE, List.of("模型误填选项"), new GeneratedMockExam.Answer(null, null, null, "分点答案", null, null), source));
        var counts = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
        var scores = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
        Arrays.stream(MockExamQuestionType.values()).forEach(type -> { counts.put(type, 1); scores.put(type, 1); });
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(), counts,
                ScoreMode.CUSTOM, scores, null, null, false, "", 8, 8);

        assertThat(validator.parse(mapper.writeValueAsString(new GeneratedMockExam(questions)), request, Set.of(source)).questions())
                .hasSize(8);
    }


    @Test void choiceQuestionsMustHaveExactlyFourOptions() throws Exception {
        var source = UUID.randomUUID();
        var invalid = typed(MockExamQuestionType.SINGLE_CHOICE, List.of("A", "B", "C"),
                new GeneratedMockExam.Answer(List.of(0), null, null, null, null, null), source);
        var request = new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 1), ScoreMode.CUSTOM,
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 2), 2, null, false, "", 1, 2);
        assertThatThrownBy(() -> validator.parse(mapper.writeValueAsString(new GeneratedMockExam(List.of(invalid))),
                request, Set.of(source))).isInstanceOfSatisfying(PermanentTaskException.class,
                error -> assertThat(error.getMessage()).contains("选项数量"));
    }    private GeneratedMockExam.Question typed(MockExamQuestionType type, List<String> options,
                                              GeneratedMockExam.Answer answer, UUID source) {
        return new GeneratedMockExam.Question(type, "题干", options, answer, 1, false, List.of(source), List.of());
    }
    private MockExamRequestNormalizer.Normalized request(boolean allowGeneral) {
        return new MockExamRequestNormalizer.Normalized(MockExamScope.WHOLE_COURSE, List.of(),
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 2), ScoreMode.CUSTOM,
                Map.of(MockExamQuestionType.SINGLE_CHOICE, 2), 4, null, allowGeneral, "", 2, 4);
    }
    private GeneratedMockExam.Question question(boolean general, List<UUID> sources) {
        return new GeneratedMockExam.Question(MockExamQuestionType.SINGLE_CHOICE, "题干", List.of("甲", "乙", "丙", "丁"),
                new GeneratedMockExam.Answer(List.of(0), null, null, null, null, null), 2,
                general, sources, List.of());
    }
}
