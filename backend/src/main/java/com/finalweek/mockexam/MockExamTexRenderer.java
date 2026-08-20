package com.finalweek.mockexam;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MockExamTexRenderer {
    private final TexEscaper escaper;
    private final MockExamFormulaValidator formulaValidator;
    private final String paperTemplate;
    private final String answerTemplate;
    public MockExamTexRenderer(TexEscaper escaper, MockExamFormulaValidator formulaValidator) {
        this.escaper = escaper; this.formulaValidator = formulaValidator;
        paperTemplate = resource("/templates/mock-exam-paper.tex");
        answerTemplate = resource("/templates/mock-exam-answer.tex");
    }
    public Documents render(MockExam exam, GeneratedMockExam generated) {
        var title = escaper.escape(exam.getPdfTitle());
        var meta = new StringBuilder();
        if (exam.getTotalScoreRequested() != null) meta.append("\\par 总分：")
                .append(exam.getTotalScoreRequested()).append(" 分");
        if (exam.getDurationMinutes() != null) meta.append("\\quad 建议时长：")
                .append(exam.getDurationMinutes()).append(" 分钟");
        return new Documents(fill(paperTemplate, title, meta.toString(), paperBody(generated)),
                fill(answerTemplate, title, "", answerBody(generated)));
    }
    private String paperBody(GeneratedMockExam generated) {
        var result = new StringBuilder(); int number = 1;
        for (var type : MockExamQuestionType.values()) {
            var values = generated.questions().stream().filter(q -> q.questionType() == type).toList();
            if (values.isEmpty()) continue;
            result.append("\\section*{").append(label(type)).append("}\n");
            for (var q : values) {
                result.append("\\textbf{").append(number++).append(".} ")
                        .append(escaper.escape(q.stem())).append(" \\hfill [").append(q.score()).append(" 分]\n");
                formulas(q, false).forEach(value -> result.append("\\[ ").append(value).append(" \\]\n"));
                if (q.options() != null && !q.options().isEmpty()) {
                    result.append("\\begin{enumerate}[label=\\Alph*.,leftmargin=2.4em,itemsep=0.2em]\n");
                    q.options().forEach(option -> result.append("\\item ").append(escaper.escape(option)).append('\n'));
                    result.append("\\end{enumerate}\n");
                }
                result.append(answerSpace(q.questionType())).append('\n');
            }
        }
        return result.toString();
    }
    private String answerBody(GeneratedMockExam generated) {
        var result = new StringBuilder(); int number = 1;
        for (var type : MockExamQuestionType.values()) {
            var values = generated.questions().stream().filter(q -> q.questionType() == type).toList();
            if (values.isEmpty()) continue;
            result.append("\\section*{").append(label(type)).append("}\n");
            for (var q : values) {
                result.append("\\textbf{").append(number++).append(".} ");
                if (q.usesGeneralKnowledge()) result.append("{\\color{orange}（使用通用知识补充）} ");
                result.append(answer(q)).append("\n\\par\n");
                formulas(q, true).forEach(value -> result.append("\\[ ").append(value).append(" \\]\n"));
            }
        }
        return result.toString();
    }
    private String answer(GeneratedMockExam.Question q) {
        var a = q.answer();
        return switch (q.questionType()) {
            case SINGLE_CHOICE, MULTIPLE_CHOICE -> a.correctOptionIndexes().stream()
                    .map(index -> String.valueOf((char) ('A' + index))).collect(java.util.stream.Collectors.joining("、"));
            case TRUE_FALSE -> Boolean.TRUE.equals(a.trueFalseAnswer()) ? "正确" : "错误";
            case FILL_BLANK -> { var parts = new ArrayList<String>(); for (int i = 0; i < a.blanks().size(); i++)
                parts.add((i + 1) + ". " + escaper.escape(a.blanks().get(i))); yield String.join("；", parts); }
            case SHORT_ANSWER, ESSAY -> escaper.escape(a.referenceAnswer());
            case CALCULATION -> {
                var parts = new ArrayList<String>(); if (a.steps() != null) a.steps().forEach(v -> parts.add(escaper.escape(v)));
                parts.add("最终答案：" + escaper.escape(a.finalAnswer())); yield String.join("\\par ", parts);
            }
        };
    }
    private List<String> formulas(GeneratedMockExam.Question q, boolean answerDocument) {
        if (q.formulas() == null) return List.of();
        return q.formulas().stream().filter(value -> answerDocument || "stem".equals(value.location()))
                .map(GeneratedMockExam.Formula::expression).peek(formulaValidator::validate).toList();
    }
    private String answerSpace(MockExamQuestionType type) {
        return switch (type) {
            case SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE -> "\\vspace{0.7em}";
            case FILL_BLANK -> "\\vspace{1.2em}";
            case SHORT_ANSWER -> "\\vspace{4em}";
            case CALCULATION -> "\\vspace{7em}";
            case ESSAY -> "\\vspace{9em}";
        };
    }
    private String label(MockExamQuestionType type) { return switch (type) {
        case SINGLE_CHOICE -> "单项选择题"; case MULTIPLE_CHOICE -> "多项选择题"; case TRUE_FALSE -> "判断题";
        case FILL_BLANK -> "填空题"; case SHORT_ANSWER -> "简答题"; case CALCULATION -> "计算题"; case ESSAY -> "论述题";
    }; }
    private String fill(String template, String title, String meta, String body) { return template
            .replace("@@TITLE@@", title).replace("@@META@@", meta).replace("@@BODY@@", body); }
    private String resource(String path) { try (InputStream input = getClass().getResourceAsStream(path)) {
        if (input == null) throw new IllegalStateException("模板不存在: " + path);
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception exception) { throw new IllegalStateException("无法读取模板: " + path, exception); } }
    public record Documents(String paperTex, String answerTex) {}
}
