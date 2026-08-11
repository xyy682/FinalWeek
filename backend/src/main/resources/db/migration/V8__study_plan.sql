CREATE TABLE study_plan (
    id BINARY(16) NOT NULL,
    course_id BINARY(16) NOT NULL,
    exam_date DATE NOT NULL,
    daily_minutes INT NOT NULL,
    mastery_level VARCHAR(10) NOT NULL,
    target_score INT NOT NULL,
    outline_generation_version BIGINT NOT NULL,
    generated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_study_plan_course UNIQUE (course_id),
    CONSTRAINT fk_study_plan_course FOREIGN KEY (course_id) REFERENCES course (id)
);

CREATE TABLE plan_generation_request (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    course_id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(10) NOT NULL,
    expected_plan_version BIGINT NOT NULL,
    result_plan_version BIGINT NULL,
    error_code VARCHAR(80) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_request_idempotency UNIQUE (user_id, course_id, idempotency_key),
    CONSTRAINT fk_plan_request_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_plan_request_course FOREIGN KEY (course_id) REFERENCES course (id),
    INDEX idx_plan_request_course_created (course_id, created_at)
);

CREATE TABLE plan_task (
    id BINARY(16) NOT NULL,
    plan_id BINARY(16) NOT NULL,
    outline_node_id BINARY(16) NOT NULL,
    knowledge_title VARCHAR(200) NOT NULL,
    planned_date DATE NOT NULL,
    estimated_minutes INT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMP(6) NULL,
    position INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_plan_task_plan FOREIGN KEY (plan_id) REFERENCES study_plan (id) ON DELETE CASCADE,
    CONSTRAINT uk_plan_task_position UNIQUE (plan_id, position),
    INDEX idx_plan_task_date (plan_id, planned_date, position)
);
