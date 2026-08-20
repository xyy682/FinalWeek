CREATE TABLE mock_exam_object_cleanup (
    id BINARY(16) NOT NULL,
    mock_exam_id BINARY(16) NOT NULL,
    paper_object_key VARCHAR(512) NULL,
    answer_object_key VARCHAR(512) NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    error_message VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mock_exam_cleanup_exam UNIQUE (mock_exam_id),
    CONSTRAINT fk_mock_exam_cleanup_exam FOREIGN KEY (mock_exam_id) REFERENCES mock_exam (id),
    INDEX idx_mock_exam_cleanup_due (status, next_attempt_at)
);
