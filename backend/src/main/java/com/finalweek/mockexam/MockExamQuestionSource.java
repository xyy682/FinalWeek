package com.finalweek.mockexam;

import java.util.UUID;

public class MockExamQuestionSource {
    private UUID questionId;
    private UUID segmentId;
    protected MockExamQuestionSource() {}
    public MockExamQuestionSource(UUID questionId, UUID segmentId) { this.questionId = questionId; this.segmentId = segmentId; }
    public UUID getQuestionId() { return questionId; }
    public UUID getSegmentId() { return segmentId; }
}
