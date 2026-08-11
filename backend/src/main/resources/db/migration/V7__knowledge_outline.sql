ALTER TABLE course
    ADD COLUMN outline_generation_seq BIGINT NOT NULL DEFAULT 0 AFTER deleted;

ALTER TABLE parse_task
    ADD COLUMN generation_version BIGINT NULL AFTER execution_round;

CREATE INDEX idx_parse_task_outline_active
    ON parse_task (course_id, task_type, status, created_at);

CREATE TABLE outline (
    id BINARY(16) NOT NULL,
    course_id BINARY(16) NOT NULL,
    generation_version BIGINT NOT NULL,
    generated_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outline_course UNIQUE (course_id),
    CONSTRAINT fk_outline_course FOREIGN KEY (course_id) REFERENCES course (id)
);

CREATE TABLE outline_node (
    id BINARY(16) NOT NULL,
    outline_id BINARY(16) NOT NULL,
    parent_id BINARY(16) NULL,
    title VARCHAR(200) NOT NULL,
    importance VARCHAR(10) NOT NULL,
    position INT NOT NULL,
    source_refs_json JSON NOT NULL,
    importance_manually_adjusted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_outline_node_outline FOREIGN KEY (outline_id) REFERENCES outline (id) ON DELETE CASCADE,
    CONSTRAINT fk_outline_node_parent FOREIGN KEY (parent_id) REFERENCES outline_node (id) ON DELETE CASCADE,
    CONSTRAINT uk_outline_node_position UNIQUE (outline_id, parent_id, position),
    INDEX idx_outline_node_outline (outline_id)
);
