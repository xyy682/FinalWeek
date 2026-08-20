package com.finalweek.mockexam;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity @Table(name = "mock_exam_question")
public class MockExamQuestion {
    @Id @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "mock_exam_id", nullable = false)
    private MockExam mockExam;
    @Column(nullable = false) private int position;
    @Column(name = "section_position", nullable = false) private int sectionPosition;
    @Enumerated(EnumType.STRING) @Column(name = "question_type", nullable = false, length = 30)
    private MockExamQuestionType questionType;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String stem;
    @Column(name = "options_json", columnDefinition = "json") private String optionsJson;
    @Column(name = "answer_json", nullable = false, columnDefinition = "json") private String answerJson;
    @Column(nullable = false) private int score;
    @Column(name = "uses_general_knowledge", nullable = false) private boolean usesGeneralKnowledge;
    @Column(name = "formula_metadata_json", nullable = false, columnDefinition = "json") private String formulaMetadataJson;
    protected MockExamQuestion() {}
    public MockExamQuestion(MockExam exam, int position, int sectionPosition, GeneratedMockExam.Question value,
                            String optionsJson, String answerJson, String formulaJson) {
        this.mockExam = exam; this.position = position; this.sectionPosition = sectionPosition;
        this.questionType = value.questionType(); this.stem = value.stem().strip(); this.optionsJson = optionsJson;
        this.answerJson = answerJson; this.score = value.score();
        this.usesGeneralKnowledge = value.usesGeneralKnowledge(); this.formulaMetadataJson = formulaJson;
    }
    public UUID getId() { return id; }
    public UUID getMockExamId() { return mockExam.getId(); }
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
