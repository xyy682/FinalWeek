package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.*;
import com.finalweek.material.CourseSegment;
import com.finalweek.task.PermanentTaskException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class MockExamQualityCheckerTest {
    @Test void directReplicationIsHardFailureAndHistoricalSimilarityIsWarning() {
        var checker = new MockExamQualityChecker(); var source = mock(CourseSegment.class);
        when(source.getContent()).thenReturn("课程资料中的一道完整原题要求学生分析事务隔离级别及其现象");
        var copied = exam("一道完整原题要求学生分析事务隔离级别及其现象");
        assertThatThrownBy(() -> checker.check(copied, List.of(source), List.of(), request(), .82, Map.of()))
                .isInstanceOf(PermanentTaskException.class);
        var old = mock(MockExamQuestion.class); when(old.getStem()).thenReturn("请比较可重复读和串行化隔离级别的差异。");
        var warnings = checker.check(exam("请比较可重复读和串行化隔离级别的差异"), List.of(), List.of(old), request(), .82, Map.of());
        assertThat(warnings).isNotEmpty();
    }
    @Test void selectedNodeCoverageUsesActualQuestionSources() {
        var checker = new MockExamQualityChecker(); var first = UUID.randomUUID(); var second = UUID.randomUUID();
        var selected = new MockExamRequestNormalizer.Normalized(MockExamScope.OUTLINE_NODES,
                List.of(first, second), Map.of(MockExamQuestionType.ESSAY, 1), ScoreMode.CUSTOM,
                Map.of(MockExamQuestionType.ESSAY, 10), 10, null, true, "", 1, 10);
        var warnings = checker.check(exam("请论述事务隔离的工程取舍"), List.of(), List.of(), selected, .82,
                Map.of(first, List.of(UUID.randomUUID()), second, List.of(UUID.randomUUID())));
        assertThat(warnings).anyMatch(value -> value.contains("2 个未被题目来源覆盖"));
    }
    private GeneratedMockExam exam(String stem) { return new GeneratedMockExam(List.of(new GeneratedMockExam.Question(
            MockExamQuestionType.ESSAY, stem, List.of(), new GeneratedMockExam.Answer(null, null, null, "答案", null, null),
            10, true, List.of(), List.of()))); }
    private MockExamRequestNormalizer.Normalized request() { return new MockExamRequestNormalizer.Normalized(
            MockExamScope.WHOLE_COURSE, List.of(), Map.of(MockExamQuestionType.ESSAY, 1), ScoreMode.CUSTOM,
            Map.of(MockExamQuestionType.ESSAY, 10), 10, null, true, "", 1, 10); }
}
