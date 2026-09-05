package com.finalweek.mockexam;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MockExamTexRendererTest {
    @Test void paperAndAnswerComeFromSameQuestionsAndOnlyAnswerMarksGeneralKnowledge() {
        var exam = mock(MockExam.class); when(exam.getPdfTitle()).thenReturn("数据库_期末%卷");
        when(exam.getTotalScoreRequested()).thenReturn(null); when(exam.getDurationMinutes()).thenReturn(null);
        var question = new GeneratedMockExam.Question(MockExamQuestionType.SINGLE_CHOICE, "1 < 2 & 为什么？",
                List.of("甲", "乙", "丙", "丁"), new GeneratedMockExam.Answer(List.of(1), null, null, null, null, null),
                2, true, List.of(), List.of(new GeneratedMockExam.Formula("1<2", "stem"),
                new GeneratedMockExam.Formula("x=42", "answer")));
        var renderer = new MockExamTexRenderer(new TexEscaper(), new MockExamFormulaValidator());
        var documents = renderer.render(exam, new GeneratedMockExam(List.of(question)));
        assertThat(documents.paperTex()).contains("数据库\\_期末\\%卷", "start=1", "\\item \\questionline{", "{2}", "\\item 乙", "\\end{enumerate}")
                .doesNotContain("使用通用知识补充", "x=42", "@@");
        assertThat(documents.answerTex()).contains("数据库\\_期末\\%卷", "1.}", "B", "使用通用知识补充", "1<2", "x=42")
                .doesNotContain("@@");
        assertThat(documents.paperTex()).doesNotContain("总分：", "建议时长：");
    }
}
