package com.finalweek.mockexam;

import java.util.UUID;

public class MockExamQuestion {
    private UUID id;
    private UUID mockExamId;
    private int position;
    private int sectionPosition;
    private MockExamQuestionType questionType;
    private String stem;
    private String optionsJson;
    private String answerJson;
    private int score;
    private boolean usesGeneralKnowledge;
    private String formulaMetadataJson;
    protected MockExamQuestion() {}
    public MockExamQuestion(MockExam exam, int position, int sectionPosition, GeneratedMockExam.Question value,
                            String optionsJson, String answerJson, String formulaJson) {
        this.mockExamId = exam.getId(); this.position = position; this.sectionPosition = sectionPosition;
        this.questionType = value.questionType(); this.stem = value.stem().strip(); this.optionsJson = optionsJson;
        this.answerJson = answerJson; this.score = value.score();
        this.usesGeneralKnowledge = value.usesGeneralKnowledge(); this.formulaMetadataJson = formulaJson;
    }
    public UUID getId() { return id; }
    public UUID getMockExamId() { return mockExamId; }
    public int getPosition() { return position; }
    public int getSectionPosition() { return sectionPosition; }
    public MockExamQuestionType getQuestionType() { return questionType; }
    public String getStem() { return stem; }
    public String getOptionsJson() { return optionsJson; }
    public String getAnswerJson() { return answerJson; }
    public int getScore() { return score; }
    public boolean isUsesGeneralKnowledge() { return usesGeneralKnowledge; }
    public String getFormulaMetadataJson() { return formulaMetadataJson; }
}
