package com.finalweek.mockexam;

import com.finalweek.common.api.BusinessException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MockExamRequestNormalizer {
    private final MockExamProperties properties;
    private static final Map<MockExamQuestionType, Integer> DEFAULT_SCORES = Map.of(
            MockExamQuestionType.SINGLE_CHOICE, 2, MockExamQuestionType.MULTIPLE_CHOICE, 4,
            MockExamQuestionType.TRUE_FALSE, 2, MockExamQuestionType.FILL_BLANK, 3,
            MockExamQuestionType.SHORT_ANSWER, 8, MockExamQuestionType.CALCULATION, 10,
            MockExamQuestionType.ESSAY, 15, MockExamQuestionType.COMPREHENSIVE, 20);
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "(?i)(请|需要|要求|使用|通过|生成).{0,8}(联网|互联网搜索|网页搜索|图片题|图表题|示意图题|看图题)|不要.{0,4}(答案|参考答案)");

    public MockExamRequestNormalizer(MockExamProperties properties) { this.properties = properties; }
    public Normalized normalize(Request input, Collection<UUID> expandedScopeIds) {
        if (input == null || input.scope() == null || input.scoreMode() == null)
            throw invalid("范围和分值模式不能为空");
        var counts = enumMap(input.questionCounts());
        counts.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() == 0);
        if (counts.isEmpty() || counts.values().stream().anyMatch(value -> value < 1))
            throw invalid("至少选择一种题型，题数必须为正整数");
        long questionCountLong = counts.values().stream().mapToLong(Integer::longValue).sum();
        if (questionCountLong < 1 || questionCountLong > properties.maxQuestions())
            throw invalid("总题数必须在 1–50 之间");
        int questionCount = Math.toIntExact(questionCountLong);
        var requestedNodes = input.outlineNodeIds() == null ? Set.<UUID>of() : new LinkedHashSet<>(input.outlineNodeIds());
        if (input.scope() == MockExamScope.OUTLINE_NODES && requestedNodes.isEmpty())
            throw invalid("限定知识点范围时至少选择一个提纲节点");
        if (input.scope() == MockExamScope.WHOLE_COURSE && !requestedNodes.isEmpty())
            throw invalid("整课范围不能同时提交知识点节点");
        var expanded = input.scope() == MockExamScope.WHOLE_COURSE ? List.<UUID>of()
                : expandedScopeIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        if (input.scope() == MockExamScope.OUTLINE_NODES && expanded.isEmpty()) throw invalid("知识点范围无效");
        if (input.durationMinutes() != null && (input.durationMinutes() < 1 || input.durationMinutes() > properties.maxDurationMinutes()))
            throw invalid("建议时长必须为 1–300 分钟");
        var instructions = Objects.toString(input.instructions(), "").strip();
        if (instructions.length() > properties.maxInstructionLength()) throw invalid("补充说明不能超过 2000 字");
        if (UNSUPPORTED.matcher(instructions).find()) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "MOCK_EXAM_INSTRUCTION_CONFLICT", "补充说明要求了联网、视觉题或省略答案等首版不支持能力");
        var customScores = enumMap(input.scorePerQuestion());
        long scoreSumLong;
        if (input.scoreMode() == ScoreMode.CUSTOM) {
            if (!customScores.keySet().equals(counts.keySet()) || customScores.values().stream().anyMatch(v -> v == null || v < 1))
                throw invalid("自定义分值必须为每个已选题型提供正整数单题分值");
            scoreSumLong = counts.entrySet().stream().mapToLong(entry ->
                    (long) entry.getValue() * customScores.get(entry.getKey())).sum();
        } else {
            customScores.clear();
            scoreSumLong = counts.entrySet().stream().mapToLong(entry ->
                    (long) entry.getValue() * DEFAULT_SCORES.get(entry.getKey())).sum();
            if (input.totalScore() != null) scoreSumLong = input.totalScore();
        }
        if (scoreSumLong < questionCount || scoreSumLong > properties.maxScore())
            throw invalid("分值汇总必须允许每题至少 1 分且不超过 1000");
        int scoreSum = Math.toIntExact(scoreSumLong);
        if (input.totalScore() != null && (input.totalScore() < 1 || input.totalScore() > properties.maxScore()))
            throw invalid("总分必须为 1–1000 的整数");
        if (input.scoreMode() == ScoreMode.CUSTOM && input.totalScore() != null && input.totalScore() != scoreSum)
            throw invalid("填写的总分必须与自定义分值汇总严格一致");
        return new Normalized(input.scope(), expanded, Collections.unmodifiableMap(counts), input.scoreMode(),
                Collections.unmodifiableMap(customScores), input.totalScore(), input.durationMinutes(),
                input.allowGeneralKnowledge() == null || input.allowGeneralKnowledge(), instructions,
                questionCount, scoreSum);
    }

    private <T> EnumMap<MockExamQuestionType, Integer> enumMap(Map<MockExamQuestionType, Integer> values) {
        var result = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
        if (values != null) result.putAll(values); return result;
    }
    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "MOCK_EXAM_REQUEST_INVALID", message);
    }

    public record Request(String displayName, MockExamScope scope, List<UUID> outlineNodeIds,
                          Map<MockExamQuestionType, Integer> questionCounts, ScoreMode scoreMode,
                          Map<MockExamQuestionType, Integer> scorePerQuestion, Integer totalScore,
                          Integer durationMinutes, Boolean allowGeneralKnowledge, String instructions) {}
    public record Normalized(MockExamScope scope, List<UUID> outlineNodeIds,
                             Map<MockExamQuestionType, Integer> questionCounts, ScoreMode scoreMode,
                             Map<MockExamQuestionType, Integer> scorePerQuestion, Integer totalScore,
                             Integer durationMinutes, boolean allowGeneralKnowledge, String instructions,
                             int questionCount, int scoreSum) {}
}
