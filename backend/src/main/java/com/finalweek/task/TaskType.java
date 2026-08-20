package com.finalweek.task;

public enum TaskType {
    PARSE_MATERIAL,
    GENERATE_OUTLINE,
    GENERATE_PLAN,
    GENERATE_MOCK_EXAM,
    ANSWER_CHAT;

    public boolean supports(TaskStage stage) {
        if (stage == TaskStage.COMPLETED) return true;
        return switch (this) {
            case PARSE_MATERIAL -> stage == TaskStage.UPLOADED || stage == TaskStage.CONTENT_EXTRACTED
                    || stage == TaskStage.CHUNKED || stage == TaskStage.EMBEDDING_COMPLETED;
            case GENERATE_OUTLINE -> stage == TaskStage.CONTEXT_RETRIEVED || stage == TaskStage.OUTLINE_GENERATED;
            case GENERATE_PLAN -> stage == TaskStage.PLAN_CONTEXT_RETRIEVED || stage == TaskStage.PLAN_GENERATED;
            case ANSWER_CHAT -> stage == TaskStage.CHAT_CONTEXT_RETRIEVED || stage == TaskStage.CHAT_ANSWER_GENERATED;
            case GENERATE_MOCK_EXAM -> stage == TaskStage.REQUIREMENTS_ANALYZED
                    || stage == TaskStage.QUESTIONS_GENERATED || stage == TaskStage.PAPER_VALIDATED
                    || stage == TaskStage.PDFS_GENERATED;
        };
    }
}
