ALTER TABLE course_segment
    ADD COLUMN token_count INT NOT NULL DEFAULT 0 AFTER chunk_no;

CREATE INDEX idx_material_retrieval_status ON material (course_id, status, deleted);
