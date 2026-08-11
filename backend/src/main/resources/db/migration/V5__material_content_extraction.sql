ALTER TABLE material
    ADD COLUMN preview_object_key VARCHAR(512) NULL AFTER object_key,
    ADD COLUMN duration_ms BIGINT NULL AFTER size_bytes,
    ADD COLUMN parse_warning VARCHAR(1000) NULL AFTER status;

CREATE TABLE course_segment (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    course_id BINARY(16) NOT NULL,
    material_id BINARY(16) NOT NULL,
    content TEXT NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    page_number INT NULL,
    slide_number INT NULL,
    paragraph_number INT NULL,
    start_time_ms BIGINT NULL,
    end_time_ms BIGINT NULL,
    asr_text TEXT NULL,
    ocr_text TEXT NULL,
    chunk_no INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_course_segment_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_course_segment_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_course_segment_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT uk_course_segment_material_chunk UNIQUE (material_id, chunk_no),
    INDEX idx_course_segment_owner (user_id, course_id, material_id)
);
