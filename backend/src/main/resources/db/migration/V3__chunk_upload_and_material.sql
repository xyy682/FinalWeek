CREATE TABLE material (
    id BINARY(16) PRIMARY KEY,
    course_id BINARY(16) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_hash CHAR(64) NULL,
    size_bytes BIGINT NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    material_type VARCHAR(30) NOT NULL,
    focus_notes VARCHAR(1000) NULL,
    status VARCHAR(30) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_material_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT uk_material_course_hash UNIQUE (course_id, content_hash),
    INDEX idx_material_course_active (course_id, deleted, created_at)
);

CREATE TABLE upload_completion (
    upload_id BINARY(16) PRIMARY KEY,
    material_id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_upload_completion_material FOREIGN KEY (material_id) REFERENCES material (id)
);
