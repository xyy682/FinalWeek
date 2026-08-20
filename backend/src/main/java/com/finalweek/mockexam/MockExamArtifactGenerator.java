package com.finalweek.mockexam;

import java.util.UUID;

public interface MockExamArtifactGenerator {
    Artifacts generate(UUID taskId, MockExam exam, GeneratedMockExam generated);
    record Artifacts(String paperObjectKey, String answerObjectKey) {}
}
