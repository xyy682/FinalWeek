package com.finalweek.mockexam;

import java.util.List;
import java.util.UUID;

public record GeneratedMockExam(Status status, List<String> missingKnowledgePoints, List<Question> questions) {
    public GeneratedMockExam(List<Question> questions) { this(Status.GENERATED, List.of(), questions); }
    public enum Status { GENERATED, INSUFFICIENT_MATERIAL }
    public record Question(MockExamQuestionType questionType, String stem, List<String> options, Answer answer,
                           int score, boolean usesGeneralKnowledge, List<UUID> sourceSegmentIds,
                           List<Formula> formulas) {}
    public record Answer(List<Integer> correctOptionIndexes, Boolean trueFalseAnswer, List<String> blanks,
                         String referenceAnswer, List<String> steps, String finalAnswer) {}
    public record Formula(String expression, String location) {}
}
