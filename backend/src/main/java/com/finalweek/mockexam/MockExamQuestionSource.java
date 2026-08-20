package com.finalweek.mockexam;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name = "mock_exam_question_source")
@IdClass(MockExamQuestionSourceId.class)
public class MockExamQuestionSource {
    @Id @Column(name = "question_id") private UUID questionId;
    @Id @Column(name = "segment_id") private UUID segmentId;
    protected MockExamQuestionSource() {}
    public MockExamQuestionSource(UUID questionId, UUID segmentId) { this.questionId = questionId; this.segmentId = segmentId; }
    public UUID getQuestionId() { return questionId; }
    public UUID getSegmentId() { return segmentId; }
}
