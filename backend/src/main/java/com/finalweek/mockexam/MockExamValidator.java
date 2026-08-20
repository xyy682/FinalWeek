package com.finalweek.mockexam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.task.PermanentTaskException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MockExamValidator {
    private final ObjectMapper mapper;
    private final MockExamFormulaValidator formulas;
    public MockExamValidator(ObjectMapper mapper, MockExamFormulaValidator formulas) {
        this.mapper = mapper; this.formulas = formulas;
    }

    public GeneratedMockExam parse(String json, MockExamRequestNormalizer.Normalized request,
                                   Set<UUID> allowedSegments) {
        final GeneratedMockExam exam;
        try { exam = mapper.readValue(json, GeneratedMockExam.class); }
        catch (Exception exception) { throw invalid("模型返回的试卷 JSON 格式不正确"); }
        if (exam.status() == null) throw invalid("模型未返回生成状态");
        if (exam.status() == GeneratedMockExam.Status.INSUFFICIENT_MATERIAL) {
            if (request.allowGeneralKnowledge()) throw invalid("允许通用知识时不得以资料不足减少题量");
            if (exam.questions() != null && !exam.questions().isEmpty()) throw invalid("资料不足响应不能包含部分试卷");
            if (exam.missingKnowledgePoints() == null || exam.missingKnowledgePoints().isEmpty()
                    || exam.missingKnowledgePoints().stream().anyMatch(value -> value == null || value.isBlank()))
                throw invalid("资料不足响应必须列出知识缺口");
            var details = String.join("、", exam.missingKnowledgePoints());
            if (details.length() > 800) details = details.substring(0, 800);
            throw new PermanentTaskException("MOCK_EXAM_SOURCE_INSUFFICIENT", "课程资料不足：" + details);
        }
        if (exam.questions() == null || exam.questions().size() != request.questionCount())
            throw invalid("试题总数与请求不一致");
        var actualCounts = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
        int score = 0;
        for (var question : exam.questions()) {
            if (question == null || question.questionType() == null || question.stem() == null
                    || question.stem().isBlank() || question.stem().length() > 10_000 || question.answer() == null)
                throw invalid("试题缺少题型、题干或答案");
            actualCounts.merge(question.questionType(), 1, Integer::sum);
            if (question.score() < 1) throw invalid("试题分值必须为正整数");
            score += question.score();
            validateAnswer(question);
            var sources = question.sourceSegmentIds() == null ? List.<UUID>of() : question.sourceSegmentIds();
            if (new LinkedHashSet<>(sources).size() != sources.size() || !allowedSegments.containsAll(sources))
                throw new PermanentTaskException("MOCK_EXAM_SOURCE_INVALID", "试题引用不属于本次知识版本上下文");
            if (question.usesGeneralKnowledge()) {
                if (!request.allowGeneralKnowledge()) throw new PermanentTaskException(
                        "MOCK_EXAM_SOURCE_INSUFFICIENT", "课程资料不足且用户禁止使用通用知识补题");
                if (!sources.isEmpty()) throw new PermanentTaskException("MOCK_EXAM_SOURCE_INVALID",
                        "通用知识题不能伪造课程资料来源");
            } else if (sources.isEmpty()) throw new PermanentTaskException("MOCK_EXAM_SOURCE_INVALID",
                    "课程资料题必须至少绑定一个来源");
            if (question.formulas() != null) question.formulas().forEach(value -> {
                if (value == null || value.expression() == null || value.expression().isBlank()
                        || value.location() == null || value.location().isBlank() || value.location().length() > 80)
                    throw invalid("公式缺少表达式或位置");
                if (!Set.of("stem", "answer").contains(value.location()))
                    throw invalid("公式位置只能是 stem 或 answer");
                formulas.validate(value.expression());
            });
        }
        if (!actualCounts.equals(request.questionCounts())) throw invalid("逐题型题数与请求不一致");
        if (score != request.scoreSum()) throw invalid("试卷分值汇总与请求不一致");
        if (request.scoreMode() == ScoreMode.CUSTOM) exam.questions().forEach(question -> {
            if (question.score() != request.scorePerQuestion().get(question.questionType()))
                throw invalid("自定义单题分值未被严格执行");
        });
        if (exam.missingKnowledgePoints() != null && !exam.missingKnowledgePoints().isEmpty())
            throw invalid("成功试卷不能同时声明知识缺口");
        return exam;
    }

    private void validateAnswer(GeneratedMockExam.Question q) {
        var options = q.options() == null ? List.<String>of() : q.options(); var answer = q.answer();
        switch (q.questionType()) {
            case SINGLE_CHOICE -> {
                validOptions(options); if (answer.correctOptionIndexes() == null
                        || answer.correctOptionIndexes().size() != 1) throw invalid("单选题必须有且仅有一个正确选项");
                validIndexes(answer.correctOptionIndexes(), options.size());
            }
            case MULTIPLE_CHOICE -> {
                validOptions(options); if (answer.correctOptionIndexes() == null
                        || new HashSet<>(answer.correctOptionIndexes()).size() < 2) throw invalid("多选题至少有两个正确选项");
                validIndexes(answer.correctOptionIndexes(), options.size());
            }
            case TRUE_FALSE -> { noOptions(options); if (answer.trueFalseAnswer() == null) throw invalid("判断题缺少答案"); }
            case FILL_BLANK -> { noOptions(options); if (answer.blanks() == null || answer.blanks().isEmpty()
                    || answer.blanks().stream().anyMatch(value -> value == null || value.isBlank())) throw invalid("填空题缺少按空答案"); }
            case SHORT_ANSWER, ESSAY -> { noOptions(options); if (blank(answer.referenceAnswer())) throw invalid("主观题缺少参考答案"); }
            case CALCULATION -> { noOptions(options); if (answer.steps() == null || answer.steps().isEmpty()
                    || blank(answer.finalAnswer())) throw invalid("计算题缺少必要步骤或最终答案"); }
        }
    }
    private void validOptions(List<String> values) {
        if (values.size() < 2 || values.size() > 8 || values.stream().anyMatch(value -> value == null || value.isBlank()))
            throw invalid("选择题选项数量或内容不正确");
    }
    private void noOptions(List<String> values) { if (!values.isEmpty()) throw invalid("非选择题不能包含选项"); }
    private void validIndexes(List<Integer> indexes, int size) {
        if (new HashSet<>(indexes).size() != indexes.size() || indexes.stream().anyMatch(i -> i == null || i < 0 || i >= size))
            throw invalid("选择题正确选项索引无效");
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private PermanentTaskException invalid(String message) {
        return new PermanentTaskException("MOCK_EXAM_FORMAT_INVALID", message);
    }
}
