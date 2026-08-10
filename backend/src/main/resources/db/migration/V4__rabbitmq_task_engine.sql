CREATE TABLE parse_task (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    course_id BINARY(16) NOT NULL,
    material_id BINARY(16) NULL,
    task_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    current_stage VARCHAR(40) NULL,
    publish_attempt_count INT NOT NULL DEFAULT 1,
    delivery_attempt_count INT NOT NULL DEFAULT 0,
    api_attempt_count INT NOT NULL DEFAULT 0,
    manual_retry_count INT NOT NULL DEFAULT 0,
    execution_round INT NOT NULL DEFAULT 0,
    business_key VARCHAR(180) NOT NULL,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(500) NULL,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_parse_task_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_parse_task_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_parse_task_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT uk_parse_task_business_key UNIQUE (business_key),
    CONSTRAINT uk_parse_task_material_type UNIQUE (material_id, task_type),
    INDEX idx_parse_task_owner (user_id, status, updated_at),
    INDEX idx_parse_task_course_status (course_id, status)
);

CREATE TABLE task_checkpoint (
    id BINARY(16) PRIMARY KEY,
    task_id BINARY(16) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    result_object_key VARCHAR(512) NULL,
    result_json JSON NULL,
    status VARCHAR(20) NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_task_checkpoint_task FOREIGN KEY (task_id) REFERENCES parse_task (id),
    CONSTRAINT uk_task_checkpoint_stage UNIQUE (task_id, stage)
);

CREATE TABLE failed_task (
    id BINARY(16) PRIMARY KEY,
    task_id BINARY(16) NOT NULL,
    message_id VARCHAR(120) NOT NULL,
    failure_stage VARCHAR(40) NULL,
    failure_reason VARCHAR(500) NOT NULL,
    redeliver_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_failed_task_task FOREIGN KEY (task_id) REFERENCES parse_task (id),
    CONSTRAINT uk_failed_task_message UNIQUE (message_id),
    INDEX idx_failed_task_task (task_id, status)
);

INSERT INTO parse_task (
    id, user_id, course_id, material_id, task_type, status, current_stage,
    publish_attempt_count, delivery_attempt_count, api_attempt_count,
    manual_retry_count, execution_round, business_key, created_at, updated_at
)
SELECT UUID_TO_BIN(UUID()), course.user_id, material.course_id, material.id,
       'PARSE_MATERIAL', material.status, 'UPLOADED', 1, 0, 0, 0, 0,
       CONCAT('PARSE_MATERIAL:', HEX(material.id)), material.created_at, material.updated_at
FROM material
JOIN course ON course.id = material.course_id
WHERE material.deleted = FALSE;
