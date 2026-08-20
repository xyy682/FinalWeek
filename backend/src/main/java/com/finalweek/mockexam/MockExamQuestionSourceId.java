package com.finalweek.mockexam;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class MockExamQuestionSourceId implements Serializable {
    private UUID questionId;
    private UUID segmentId;
    protected MockExamQuestionSourceId() {}
    public MockExamQuestionSourceId(UUID questionId, UUID segmentId) {
        this.questionId = questionId; this.segmentId = segmentId;
    }
    @Override public boolean equals(Object value) { return value instanceof MockExamQuestionSourceId other
            && Objects.equals(questionId, other.questionId) && Objects.equals(segmentId, other.segmentId); }
    @Override public int hashCode() { return Objects.hash(questionId, segmentId); }
}
