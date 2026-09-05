package com.finalweek.mockexam;

import com.finalweek.material.CourseSegment;
import com.finalweek.task.PermanentTaskException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MockExamQualityChecker {
    public List<String> check(GeneratedMockExam generated, List<CourseSegment> sourceSegments,
                              List<MockExamQuestion> history, MockExamRequestNormalizer.Normalized request,
                              double historySimilarityThreshold, Map<UUID, List<UUID>> nodeSegmentIds) {
        var sourceTexts = sourceSegments.stream().map(value -> normalize(value.getContent())).filter(value -> value.length() >= 20).toList();
        var historical = history.stream().map(value -> normalize(value.getStem())).toList();
        var warnings = new LinkedHashSet<String>();
        for (var question : generated.questions()) {
            var stem = normalize(question.stem());
            if (stem.length() >= 20 && sourceTexts.stream().anyMatch(source -> replicatesSource(stem, source)))
                throw new PermanentTaskException("MOCK_EXAM_SOURCE_REPLICATED", "试题直接复刻了课程资料中的原题");
            if (historical.stream().anyMatch(old -> similarity(stem, old) >= historySimilarityThreshold))
                warnings.add("部分题目与本课程历史模拟卷较为相似");
        }
        if (request.scope() == MockExamScope.OUTLINE_NODES) {
            var used = generated.questions().stream().filter(question -> !question.usesGeneralKnowledge())
                    .flatMap(question -> Optional.ofNullable(question.sourceSegmentIds()).orElse(List.of()).stream())
                    .collect(java.util.stream.Collectors.toSet());
            long uncovered = request.outlineNodeIds().stream().filter(nodeId ->
                    nodeSegmentIds.getOrDefault(nodeId, List.of()).stream().noneMatch(used::contains)).count();
            if (uncovered > 0) warnings.add("所选知识点中有 " + uncovered + " 个未被题目来源覆盖，已优先安排重点内容");
        }
        return List.copyOf(warnings);
    }
    private String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", ""); }
    private double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        var a = grams(left); var b = grams(right); var intersection = new HashSet<>(a); intersection.retainAll(b);
        var union = new HashSet<>(a); union.addAll(b); return union.isEmpty() ? 0 : intersection.size() / (double) union.size();
    }
    private boolean replicatesSource(String stem, String source) {
        if (source.contains(stem)) return true;
        int shorter = Math.min(stem.length(), source.length());
        if (shorter >= 80 && stem.contains(source)) return true;
        var stemGrams = grams(stem); var sourceGrams = grams(source); var common = new HashSet<>(stemGrams);
        common.retainAll(sourceGrams); int denominator = Math.min(stemGrams.size(), sourceGrams.size());
        return shorter >= 80 && denominator > 0 && common.size() / (double) denominator >= .90;
    }
    private Set<String> grams(String value) {
        var result = new HashSet<String>(); if (value.length() == 1) result.add(value);
        for (int i = 0; i + 1 < value.length(); i++) result.add(value.substring(i, i + 2)); return result;
    }
}
