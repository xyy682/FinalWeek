package com.finalweek.mockexam;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MockExamPublisher {
    private final MockExamRepository exams;
    private final MockExamQuestionRepository questions;
    private final MockExamQuestionSourceRepository sources;
    private final ObjectMapper mapper;
    public MockExamPublisher(MockExamRepository exams, MockExamQuestionRepository questions,
                             MockExamQuestionSourceRepository sources, ObjectMapper mapper) {
        this.exams = exams; this.questions = questions; this.sources = sources; this.mapper = mapper;
    }
    @Transactional
    public void publishQuestions(UUID examId, GeneratedMockExam generated, List<String> warnings) {
        if (!questions.findAllByMockExam_IdOrderByPosition(examId).isEmpty()) return;
        var exam = exams.findById(examId).orElseThrow();
        var sections = new EnumMap<MockExamQuestionType, Integer>(MockExamQuestionType.class); int nextSection = 0;
        try {
            for (int i = 0; i < generated.questions().size(); i++) {
                var value = generated.questions().get(i);
                var section = sections.get(value.questionType());
                if (section == null) { section = nextSection++; sections.put(value.questionType(), section); }
                var question = questions.saveAndFlush(new MockExamQuestion(exam, i, section, value,
                        value.options() == null ? null : mapper.writeValueAsString(value.options()),
                        mapper.writeValueAsString(value.answer()), mapper.writeValueAsString(
                                value.formulas() == null ? List.of() : value.formulas())));
                if (value.sourceSegmentIds() != null) value.sourceSegmentIds()
                        .forEach(segmentId -> sources.save(new MockExamQuestionSource(question.getId(), segmentId)));
            }
            exam.warnings(mapper.writeValueAsString(warnings)); exams.save(exam);
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("模拟卷结构无法保存", exception); }
    }
}
