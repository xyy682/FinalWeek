CREATE TABLE mock_exam (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    course_id BINARY(16) NOT NULL,
    knowledge_version_id BINARY(16) NOT NULL,
    task_id BINARY(16) NULL,
    retry_of_id BINARY(16) NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    pdf_title VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL,
    request_json JSON NOT NULL,
    request_hash CHAR(64) NOT NULL,
    allow_general_knowledge BOOLEAN NOT NULL,
    question_count INT NOT NULL,
    score_sum INT NOT NULL,
    total_score_requested INT NULL,
    duration_minutes INT NULL,
    warnings_json JSON NOT NULL,
    error_code VARCHAR(80) NULL,
    paper_object_key VARCHAR(512) NULL,
    answer_object_key VARCHAR(512) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    deleted_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mock_exam_task UNIQUE (task_id),
    CONSTRAINT uk_mock_exam_idempotency UNIQUE (user_id, course_id, idempotency_key),
    CONSTRAINT fk_mock_exam_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_mock_exam_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_mock_exam_knowledge_version FOREIGN KEY (knowledge_version_id)
        REFERENCES course_knowledge_version (id),
    CONSTRAINT fk_mock_exam_task FOREIGN KEY (task_id) REFERENCES background_task (id),
    CONSTRAINT fk_mock_exam_retry FOREIGN KEY (retry_of_id) REFERENCES mock_exam (id),
    INDEX idx_mock_exam_course_created (course_id, created_at, id),
    INDEX idx_mock_exam_knowledge_version (knowledge_version_id)
);

CREATE TABLE mock_exam_question (
    id BINARY(16) NOT NULL,
    mock_exam_id BINARY(16) NOT NULL,
    position INT NOT NULL,
    section_position INT NOT NULL,
    question_type VARCHAR(30) NOT NULL,
    stem LONGTEXT NOT NULL,
    options_json JSON NULL,
    answer_json JSON NOT NULL,
    score INT NOT NULL,
    uses_general_knowledge BOOLEAN NOT NULL,
    formula_metadata_json JSON NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_mock_exam_question_exam FOREIGN KEY (mock_exam_id)
        REFERENCES mock_exam (id) ON DELETE CASCADE,
    CONSTRAINT uk_mock_exam_question_position UNIQUE (mock_exam_id, position),
    INDEX idx_mock_exam_question_section (mock_exam_id, section_position, position)
);

CREATE TABLE mock_exam_question_source (
    question_id BINARY(16) NOT NULL,
    segment_id BINARY(16) NOT NULL,
    PRIMARY KEY (question_id, segment_id),
    CONSTRAINT fk_mock_exam_source_question FOREIGN KEY (question_id)
        REFERENCES mock_exam_question (id) ON DELETE CASCADE,
    CONSTRAINT fk_mock_exam_source_segment FOREIGN KEY (segment_id)
        REFERENCES course_segment (id)
);
