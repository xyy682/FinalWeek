package com.finalweek.mockexam;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MockExamScoreAllocator {
    public GeneratedMockExam allocate(GeneratedMockExam exam, MockExamRequestNormalizer.Normalized request) {
        var assigned = request.scoreMode() == ScoreMode.CUSTOM
                ? customScores(exam, request)
                : autoScores(exam.questions(), request.scoreSum());
        var questions = new ArrayList<GeneratedMockExam.Question>(exam.questions().size());
        for (int i = 0; i < exam.questions().size(); i++) {
            var q = exam.questions().get(i);
            questions.add(new GeneratedMockExam.Question(q.questionType(), q.stem(), q.options(), q.answer(),
                    assigned.get(i), q.usesGeneralKnowledge(), q.sourceSegmentIds(), q.formulas()));
        }
        return new GeneratedMockExam(exam.status(), exam.missingKnowledgePoints(), List.copyOf(questions));
    }

    private List<Integer> customScores(GeneratedMockExam exam, MockExamRequestNormalizer.Normalized request) {
        return exam.questions().stream().map(q -> request.scorePerQuestion().get(q.questionType())).toList();
    }

    private List<Integer> autoScores(List<GeneratedMockExam.Question> questions, int target) {
        if (questions.isEmpty() || target < questions.size())
            throw new IllegalArgumentException("总分不能小于总题数");
        var counts = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
        questions.forEach(q -> counts.merge(q.questionType(), 1, Integer::sum));
        var types = new ArrayList<>(counts.keySet());
        var defaults = Map.of(MockExamQuestionType.SINGLE_CHOICE, 2, MockExamQuestionType.MULTIPLE_CHOICE, 4,
                MockExamQuestionType.TRUE_FALSE, 2, MockExamQuestionType.FILL_BLANK, 3,
                MockExamQuestionType.SHORT_ANSWER, 8, MockExamQuestionType.CALCULATION, 10,
                MockExamQuestionType.ESSAY, 15, MockExamQuestionType.COMPREHENSIVE, 20);
        int defaultTotal = types.stream().mapToInt(t -> counts.get(t) * defaults.get(t)).sum();
        record State(double cost, List<Integer> values) {}
        var dp = new HashMap<Integer, State>();
        dp.put(0, new State(0, List.of()));
        for (var type : types) {
            var next = new HashMap<Integer, State>();
            int count = counts.get(type);
            double ideal = defaults.get(type) * target / (double) defaultTotal;
            for (var entry : dp.entrySet()) for (int value = 1; entry.getKey() + count * value <= target; value++) {
                int sum = entry.getKey() + count * value;
                double cost = entry.getValue().cost() + count * Math.pow(value - ideal, 2);
                var values = new ArrayList<>(entry.getValue().values()); values.add(value);
                var old = next.get(sum);
                if (old == null || cost < old.cost()) next.put(sum, new State(cost, List.copyOf(values)));
            }
            dp = next;
        }
        var exact = dp.get(target);
        if (exact != null) {
            var byType = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class);
            for (int i = 0; i < types.size(); i++) byType.put(types.get(i), exact.values().get(i));
            return questions.stream().map(q -> byType.get(q.questionType())).toList();
        }
        var result = new int[questions.size()]; Arrays.fill(result, 1);
        var weights = questions.stream().mapToDouble(q -> defaults.get(q.questionType())).toArray();
        int remaining = target - questions.size(); double weightSum = Arrays.stream(weights).sum();
        var fractions = new ArrayList<Integer>(); int used = 0;
        for (int i = 0; i < result.length; i++) { double share = remaining * weights[i] / weightSum;
            int floor = (int) Math.floor(share); result[i] += floor; used += floor; fractions.add(i); }
        fractions.sort(Comparator.<Integer>comparingDouble(i -> -(remaining * weights[i] / weightSum % 1)).thenComparingInt(i -> i));
        for (int i = 0; i < remaining - used; i++) result[fractions.get(i)]++;
        return Arrays.stream(result).boxed().toList();
    }
}
